package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.DAOService.DataDAO;
import com.revature.DAOService.LoadResult;
import com.revature.DAOService.SaveResult;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.ConsoleCommandHandler;
import com.revature.cardealer.ConsoleIO;
import com.revature.cardealer.Data;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.SnapshotRehydrator;
import com.revature.cardealer.User;
import com.revature.service.InMemoryUserAccountRepository;
import com.revature.service.RoleAuthorizationService;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/** Direct command/adapter calls must not bypass administrator authorization. */
@Tag("SECURITY")
class SnapshotAuthorizationBoundaryTest {

    @Test
    void directCommandsAndAdapterFailClosedBeforeAnyStorageIO() {
        CountingStorage store = new CountingStorage();
        InMemoryUserAccountRepository accounts = new InMemoryUserAccountRepository();
        DealershipApplicationContext context = new DealershipApplicationContext(accounts,
                new RoleAuthorizationService(), new SnapshotRehydrator(), store);
        TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "root", "TestPass123!");
        TestAccounts.seed(accounts, AccountRole.CUSTOMER, "buyer", "TestPass456!");
        User admin = AuthenticatedAccounts.login(context,
                AccountRole.ADMINISTRATOR, "root", "TestPass123!");
        User customer = AuthenticatedAccounts.login(context,
                AccountRole.CUSTOMER, "buyer", "TestPass456!");
        User forged = new User();
        forged.setUsername(admin.getUsername());
        forged.setPassword(admin.getPassword());
        forged.setRole(AccountRole.ADMINISTRATOR);
        forged.setSessionRevision(admin.getSessionRevision());

        SaveStateCommand save = new SaveStateCommand(context);
        LoadStateCommand load = new LoadStateCommand(context);
        ContextPersistenceAdapter adapter = new ContextPersistenceAdapter(context);

        assertThrows(SecurityException.class, () -> save.execute("CarDealer.dat"));
        assertThrows(SecurityException.class, () -> load.execute("CarDealer.dat"));
        assertThrows(SecurityException.class, () -> save.execute(null, "CarDealer.dat"));
        assertThrows(SecurityException.class, () -> load.execute(null, "CarDealer.dat"));
        assertThrows(SecurityException.class, () -> save.execute(customer, "CarDealer.dat"));
        assertThrows(SecurityException.class, () -> load.execute(customer, "CarDealer.dat"));
        assertThrows(SecurityException.class, () -> save.execute(forged, "CarDealer.dat"));
        assertThrows(SecurityException.class, () -> load.execute(forged, "CarDealer.dat"));
        assertThrows(SecurityException.class,
                () -> adapter.save(customer, new Data(), "CarDealer.dat"));
        assertThrows(SecurityException.class,
                () -> adapter.load(forged, "CarDealer.dat"));
        assertThrows(SecurityException.class,
                () -> adapter.activateLoadedSnapshot(customer,
                        LoadResult.failure(LoadResult.Status.NOT_FOUND, "none")));
        assertThrows(SecurityException.class,
                () -> context.getDataStore());
        assertThrows(SecurityException.class,
                () -> adapter.load(admin, "../outside-CarDealer.dat"));
        assertThrows(SecurityException.class,
                () -> adapter.save(admin, new Data(), "../outside-CarDealer.dat"));
        assertEquals(0, store.loads);
        assertEquals(0, store.saves);

        assertTrue(save.execute(admin, "CarDealer.dat").isSuccess());
        assertEquals(LoadResult.Status.NOT_FOUND, load.execute(admin, "CarDealer.dat").getStatus());
        assertEquals(1, store.saves);
        assertEquals(1, store.loads);

        assertThrows(SecurityException.class, () -> new ConsoleCommandHandler(context, new ConsoleIO() {
            @Override public String readLine() { return ""; }
            @Override public void writeLine(String value) { }
        }).saveData());
        assertEquals(1, store.saves);

        assertTrue(accounts.updateRoleIfCurrent("root",
                AccountRole.ADMINISTRATOR, AccountRole.CUSTOMER));
        assertThrows(SecurityException.class, () -> save.execute(admin, "CarDealer.dat"));
        assertThrows(SecurityException.class, () -> load.execute(admin, "CarDealer.dat"));
        assertEquals(1, store.saves);
        assertEquals(1, store.loads);
    }

    private static final class CountingStorage implements DataDAO {
        private int saves;
        private int loads;
        @Override public SaveResult saveData(Data value, String filename) {
            saves++;
            return SaveResult.success(filename);
        }
        @Override public LoadResult loadData(String filename) {
            loads++;
            return LoadResult.failure(LoadResult.Status.NOT_FOUND, "expected missing fixture");
        }
    }
}
