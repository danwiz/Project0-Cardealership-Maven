package com.revature.cardealer;

import java.util.Objects;

import com.revature.DAOService.DAOService;
import com.revature.DAOService.DataDAO;
import com.revature.DAOService.LoadResult;
import com.revature.DAOService.SaveResult;
import com.revature.service.Permission;
import com.revature.service.AdminLoginService;
import com.revature.service.CustomerLoginService;
import com.revature.service.EmployeeLoginService;
import com.revature.service.InMemoryUserAccountRepository;
import com.revature.service.ReadOnlyUserAccountRepository;
import com.revature.service.RoleAuthorizationService;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;

/** Owns the mutable runtime graph independently of any user-interface adapter. */
public final class DealershipApplicationContext {

    private UserAccountRepository accountRepository;
    private final boolean restrictedAdministration;
    private UserAccountRepository publicAccountView;
    private UserLoginService userLoginService;
    private CustomerLoginService customerLoginService;
    private EmployeeLoginService employeeLoginService;
    private AdminLoginService adminLoginService;
    private Offer inventory;
    private Payments payments;
    private final RoleAuthorizationService authorizationService;
    private final SnapshotRehydrator snapshotRehydrator;
    private final DataDAO dataStore;
    private User currentAccount;
    private User latestCustomer = new User();
    private User latestEmployee = new User();

    public DealershipApplicationContext() {
        this(new InMemoryUserAccountRepository(), new RoleAuthorizationService(),
                new SnapshotRehydrator(), new DAOService());
    }

    public DealershipApplicationContext(UserAccountRepository repository,
            RoleAuthorizationService authorizationService,
            SnapshotRehydrator snapshotRehydrator, DataDAO dataStore) {
        this(repository, authorizationService, snapshotRehydrator, dataStore, repository);
    }

    /**
     * With a null administrative repository, normal runtime services cannot
     * provision privileged accounts, reassign roles or delete accounts.
     */
    public DealershipApplicationContext(UserAccountRepository repository,
            RoleAuthorizationService authorizationService,
            SnapshotRehydrator snapshotRehydrator, DataDAO dataStore,
            UserAccountRepository administrativeRepository) {
        this.accountRepository = Objects.requireNonNull(repository, "repository");
        this.restrictedAdministration = administrativeRepository == null;
        this.publicAccountView = new ReadOnlyUserAccountRepository(this.accountRepository);
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.authorizationService.requireVerifiedSessions(() -> this.accountRepository);
        this.snapshotRehydrator = Objects.requireNonNull(snapshotRehydrator, "snapshotRehydrator");
        this.dataStore = Objects.requireNonNull(dataStore, "dataStore");
        this.userLoginService = new UserLoginService(repository, administrativeRepository);
        this.customerLoginService = new CustomerLoginService(repository, administrativeRepository);
        this.employeeLoginService = new EmployeeLoginService(repository, administrativeRepository);
        this.adminLoginService = new AdminLoginService(repository, administrativeRepository);
        this.inventory = new Offer();
        this.payments = new Payments();
    }

    public void replaceRuntime(RehydratedApplicationState replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (restrictedAdministration) {
            throw new SecurityException("runtime snapshot replacement requires a separately authorized operator");
        }
        accountRepository = replacement.getAccountRepository();
        publicAccountView = new ReadOnlyUserAccountRepository(accountRepository);
        userLoginService = replacement.getUserLoginService();
        customerLoginService = replacement.getCustomerLoginService();
        employeeLoginService = replacement.getEmployeeLoginService();
        adminLoginService = replacement.getAdminLoginService();
        inventory = replacement.getInventory();
        payments = replacement.getPayments();
        currentAccount = null;
        authorizationService.clearVerifiedSessions();
    }

    public Data snapshotSource() {
        return new Data(customerLoginService, employeeLoginService, latestEmployee,
                latestCustomer, inventory, payments);
    }

    /** Read-only, credential-redacted view; privileged writes stay with identity services. */
    public UserAccountRepository getAccountRepository() { return publicAccountView; }
    public UserLoginService getUserLoginService() { return userLoginService; }
    public CustomerLoginService getCustomerLoginService() { return customerLoginService; }
    public EmployeeLoginService getEmployeeLoginService() { return employeeLoginService; }
    public AdminLoginService getAdminLoginService() { return adminLoginService; }
    public Offer getInventory() { return inventory; }
    public Payments getPayments() { return payments; }
    public RoleAuthorizationService getAuthorizationService() { return authorizationService; }
    public SnapshotRehydrator getSnapshotRehydrator() { return snapshotRehydrator; }
    /** Historical no-actor data-store escape hatch is disabled. */
    @Deprecated
    public DataDAO getDataStore() {
        throw new SecurityException("direct snapshot IO requires authenticated persistence commands");
    }

    /** Operation-level authorization is mandatory before any snapshot disk IO. */
    public SaveResult saveAuthorizedSnapshot(User actor, Data data, String filename) {
        requireSnapshotAuthority(actor, filename);
        return dataStore.saveData(data, filename);
    }

    public LoadResult loadAuthorizedSnapshot(User actor, String filename) {
        requireSnapshotAuthority(actor, filename);
        return dataStore.loadData(filename);
    }

    private void requireSnapshotAuthority(User actor, String filename) {
        if (!authorizationService.enforcesVerifiedSessions()) {
            throw new SecurityException("verified sessions required for snapshot IO");
        }
        authorizationService.requireAuthorized(actor, Permission.MANAGE_PERSISTENCE);
        if (!"CarDealer.dat".equals(filename)) {
            throw new SecurityException("unapproved snapshot file");
        }
    }
    public User getCurrentAccount() { return currentAccount; }
    public void setCurrentAccount(User currentAccount) {
        this.currentAccount = currentAccount;
        if (currentAccount == null) authorizationService.clearVerifiedSessions();
    }
    public void setLatestCustomer(User latestCustomer) { this.latestCustomer = latestCustomer; }
    public void setLatestEmployee(User latestEmployee) { this.latestEmployee = latestEmployee; }
}
