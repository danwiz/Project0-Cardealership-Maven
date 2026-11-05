package com.revature.cardealer;

import com.revature.service.AdminLoginService;
import com.revature.service.CustomerLoginService;
import com.revature.service.EmployeeLoginService;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;

/** Fully rebuilt runtime graph produced from a validated snapshot. */
public final class RehydratedApplicationState {

    private final UserAccountRepository accountRepository;
    private final UserLoginService userLoginService;
    private final CustomerLoginService customerLoginService;
    private final EmployeeLoginService employeeLoginService;
    private final AdminLoginService adminLoginService;
    private final Offer inventory;
    private final Payments payments;

    public RehydratedApplicationState(UserAccountRepository accountRepository,
            UserLoginService userLoginService, CustomerLoginService customerLoginService,
            EmployeeLoginService employeeLoginService, AdminLoginService adminLoginService,
            Offer inventory, Payments payments) {
        this.accountRepository = accountRepository;
        this.userLoginService = userLoginService;
        this.customerLoginService = customerLoginService;
        this.employeeLoginService = employeeLoginService;
        this.adminLoginService = adminLoginService;
        this.inventory = inventory;
        this.payments = payments;
    }

    public UserAccountRepository getAccountRepository() { return accountRepository; }
    public UserLoginService getUserLoginService() { return userLoginService; }
    public CustomerLoginService getCustomerLoginService() { return customerLoginService; }
    public EmployeeLoginService getEmployeeLoginService() { return employeeLoginService; }
    public AdminLoginService getAdminLoginService() { return adminLoginService; }
    public Offer getInventory() { return inventory; }
    public Payments getPayments() { return payments; }
}
