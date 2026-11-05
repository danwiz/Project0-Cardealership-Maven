package com.revature.application;

import java.util.Objects;

import com.revature.DAOService.DAOService;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.SnapshotRehydrator;
import com.revature.repository.CustomerOwnershipRepository;
import com.revature.repository.InventoryRepository;
import com.revature.repository.LedgerPaymentTransactionRepository;
import com.revature.repository.OfferInventoryRepository;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcInventoryRepository;
import com.revature.repository.jdbc.JdbcOwnershipRepository;
import com.revature.repository.jdbc.JdbcPaymentProcessor;
import com.revature.repository.jdbc.JdbcPurchaseApprovalService;
import com.revature.repository.jdbc.JdbcPaymentTransactionRepository;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.InMemoryUserAccountRepository;
import com.revature.service.RoleAuthorizationService;
import com.revature.service.UserAccountRepository;

/** Selects and wires infrastructure adapters without changing application use cases. */
public final class DealershipCompositionRoot {
    private DealershipCompositionRoot() { }

    public static ConfiguredDealershipApplication createDefault() {
        return create(InfrastructureConfiguration.fromSystemProperties());
    }

    public static ConfiguredDealershipApplication create(InfrastructureConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.getMode() == InfrastructureConfiguration.Mode.JDBC) {
            return createJdbc(configuration);
        }
        return createInMemory(configuration);
    }

    private static ConfiguredDealershipApplication createInMemory(InfrastructureConfiguration configuration) {
        UserAccountRepository accounts = new InMemoryUserAccountRepository();
        DealershipApplicationContext context = newContext(accounts, accounts);
        InventoryRepository inventory = new OfferInventoryRepository(context.getInventory());
        CustomerOwnershipRepository ownership = new CustomerOwnershipRepository(context.getCustomerLoginService());
        LedgerPaymentTransactionRepository payments = new LedgerPaymentTransactionRepository(context.getPayments());
        return new ConfiguredDealershipApplication(configuration, context, accounts, inventory,
                customer -> ownership, customer -> payments,
                customer -> new InMemoryPaymentProcessor(ownership, payments), null);
    }

    private static ConfiguredDealershipApplication createJdbc(InfrastructureConfiguration configuration) {
        JdbcDatabase database = new JdbcDatabase(configuration.getJdbcUrl(),
                configuration.getJdbcUser(), configuration.getJdbcPassword());
        database.ensureSchemaReady();
        UserAccountRepository accounts = new JdbcUserAccountRepository(database);
        DealershipApplicationContext context = newContext(accounts, null);
        InventoryRepository inventory = new JdbcInventoryRepository(database);
        JdbcPaymentProcessor paymentProcessor = new JdbcPaymentProcessor(database);
        return new ConfiguredDealershipApplication(configuration, context, accounts, inventory,
                customer -> new JdbcOwnershipRepository(database, customer),
                customer -> new JdbcPaymentTransactionRepository(database, customer),
                customer -> paymentProcessor,
                new JdbcPurchaseApprovalService(database, context.getAuthorizationService()));
    }

    private static DealershipApplicationContext newContext(UserAccountRepository accounts,
            UserAccountRepository administrativeAccounts) {
        return new DealershipApplicationContext(accounts, new RoleAuthorizationService(),
                new SnapshotRehydrator(), new DAOService(), administrativeAccounts);
    }
}
