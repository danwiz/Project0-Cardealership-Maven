package com.revature.cardealer;

import java.io.Serializable;

import com.revature.service.CustomerLoginService;
import com.revature.service.EmployeeLoginService;

/**
 * Runtime state wrapper that also assembles the durable application snapshot.
 */
public class Data implements Serializable {

    private static final long serialVersionUID = 1L;

    private transient EmployeeLoginService dEls;
    private transient CustomerLoginService dCls;
    private User dCustomer;
    private User dEmployee;
    private transient Offer dCarlot;
    private transient Payments dPayments;
    private ApplicationStateSnapshot snapshot;
    private String caption;

    public Data() {
    }

    public Data(CustomerLoginService customerLoginService,
            EmployeeLoginService employeeLoginService,
            User employee,
            User customer,
            Offer carlot,
            Payments payments) {
        dCls = customerLoginService;
        dEls = employeeLoginService;
        dCustomer = customer;
        dEmployee = employee;
        dCarlot = carlot;
        dPayments = payments;
        rebuildSnapshot();
    }

    public void rebuildSnapshot() {
        if (dCls == null || dCarlot == null || dPayments == null) {
            throw new IllegalStateException("customer service, inventory, and payments are required");
        }
        snapshot = new ApplicationStateSnapshot(
                dCls.getUsers(),
                dCarlot.getListings(),
                dCarlot.getPurchaseRequests(),
                dCls.getOwnedVehicleRecords(),
                dPayments.getTransactions());
    }

    public ApplicationStateSnapshot getSnapshot() {
        if (snapshot == null) {
            throw new IllegalStateException("application snapshot has not been assembled");
        }
        return snapshot;
    }

    public void setSnapshot(ApplicationStateSnapshot snapshot) { this.snapshot = snapshot; }
    public void setCustomerLoginService(CustomerLoginService value) { dCls = value; }
    public void setEmployeeLoginService(EmployeeLoginService value) { dEls = value; }
    public EmployeeLoginService getEmployeeLoginService() { return dEls; }
    public CustomerLoginService getCustomerLoginService() { return dCls; }
    public void setOffer(Offer value) { dCarlot = value; }
    public Offer getOffer() { return dCarlot; }
    public Payments getPayments() { return dPayments; }
    public User getCustomer() { return dCustomer; }
    public void setCustomer(User value) { dCustomer = value; }
    public User getEmployee() { return dEmployee; }
    public void setEmployee(User value) { dEmployee = value; }
    public String getCaption() { return caption; }
    public void setCaption(String value) { caption = value; }

    @Override
    public String toString() {
        ApplicationStateSnapshot state = getSnapshot();
        return "[ApplicationState v" + state.getVersion() + "] Accounts=" + state.getAccounts().size()
                + " Inventory=" + state.getInventoryListings().size()
                + " Requests=" + state.getPurchaseRequests().size()
                + " OwnedVehicles=" + state.getOwnedVehicles().size()
                + " Transactions=" + state.getPaymentTransactions().size();
    }
}
