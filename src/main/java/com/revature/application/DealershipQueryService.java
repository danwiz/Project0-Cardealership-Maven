package com.revature.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.InventoryListing;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PaymentPlan;
import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.Payments;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.repository.CustomerOwnershipRepository;
import com.revature.repository.InventoryRepository;
import com.revature.repository.LedgerPaymentTransactionRepository;
import com.revature.repository.OfferInventoryRepository;
import com.revature.repository.OwnershipRepository;
import com.revature.repository.PaymentTransactionRepository;
import com.revature.service.Permission;
import com.revature.service.RoleAuthorizationService;

/** Read-only application service returning presentation-neutral response models. */
public final class DealershipQueryService {
    private final DealershipApplicationContext context;
    private final ConfiguredDealershipApplication application;
    private final RoleAuthorizationService authorization;

    public DealershipQueryService(DealershipApplicationContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.context = context;
        this.application = null;
        this.authorization = context.getAuthorizationService();
    }

    public DealershipQueryService(ConfiguredDealershipApplication application) {
        if (application == null) throw new IllegalArgumentException("application must not be null");
        this.application = application;
        this.context = application.getContext();
        this.authorization = context.getAuthorizationService();
    }

    public AccountView account(User actor) {
        if (actor == null) throw new IllegalArgumentException("account must not be null");
        return new AccountView(actor.getUsername(), actor.getRole());
    }

    public List<InventoryView> inventory(User actor) {
        boolean allowed = authorization.isAuthorized(actor, Permission.VIEW_INVENTORY)
                || authorization.isAuthorized(actor, Permission.MANAGE_INVENTORY);
        if (!allowed) throw new SecurityException("account is not authorized to view inventory");
        List<InventoryView> result = new ArrayList<>();
        for (InventoryListing listing : inventoryRepository().listings()) if (listing.isActive()) result.add(toInventoryView(listing));
        return immutable(result);
    }

    public List<PurchaseRequestView> pendingRequests(User actor) {
        authorization.requireAuthorized(actor, Permission.REVIEW_PURCHASE_REQUESTS);
        List<PurchaseRequestView> result = new ArrayList<>();
        for (PurchaseRequest request : inventoryRepository().purchaseRequests()) if (request.isPending()) result.add(toRequestView(request));
        return immutable(result);
    }

    public List<OwnershipView> ownership(User actor) {
        authorization.requireAuthorized(actor, Permission.VIEW_OWNED_VEHICLES);
        List<OwnershipView> result = new ArrayList<>();
        for (OwnedVehicle owned : ownershipRepository(actor.getUsername()).findAll()) {
            PaymentPlan plan = owned.getPaymentPlan();
            Car car = owned.getVehicle();
            result.add(new OwnershipView(owned.getOwnershipId(), owned.getContractId().orElse(null),
                    car.getCarMake(), car.getCarModel(), car.getCarYear(), plan.getPurchasePrice(),
                    plan.getAmountPaid(), plan.getRemainingBalance(), plan.getMonthlyPayment(), plan.getTermMonths()));
        }
        return immutable(result);
    }

    public PaymentReportView payments(User actor) {
        if (actor == null) throw new IllegalArgumentException("account must not be null");
        boolean sharedInMemoryLedger = application == null
                || application.getConfiguration().getMode() == InfrastructureConfiguration.Mode.IN_MEMORY;
        if (actor.getRole() != AccountRole.CUSTOMER && !sharedInMemoryLedger) {
            throw new IllegalArgumentException("customer username is required for staff payment reports");
        }
        return payments(actor, actor.getUsername());
    }

    public PaymentReportView payments(User actor, String customerName) {
        Permission required = actor != null && actor.getRole() == AccountRole.CUSTOMER
                ? Permission.VIEW_OWN_PAYMENTS : Permission.VIEW_CUSTOMER_PAYMENTS;
        authorization.requireAuthorized(actor, required);
        if (actor != null && actor.getRole() == AccountRole.CUSTOMER && !actor.getUsername().equals(customerName)) {
            throw new SecurityException("customers may only view their own payments");
        }
        PaymentTransactionRepository repository = paymentRepository(customerName);
        List<PaymentTransactionView> transactions = new ArrayList<>();
        for (PaymentTransaction transaction : repository.findAll()) {
            OptionalLong ownershipId = transaction.getOwnershipId();
            transactions.add(new PaymentTransactionView(transaction.getTransactionId(), transaction.getCustomerName(),
                    ownershipId.isPresent() ? Long.valueOf(ownershipId.getAsLong()) : null,
                    transaction.getContractId().orElse(null), transaction.getAmount(), transaction.getTotalPaid(),
                    transaction.getRemainingBalance(), transaction.getRecordedAt()));
        }
        Payments ledger = repository.ledger();
        return new PaymentReportView(ledger.getAmtOwed(), ledger.getTotalPaid(), ledger.getBalance(), immutable(transactions));
    }

    private InventoryRepository inventoryRepository() {
        return application == null ? new OfferInventoryRepository(context.getInventory()) : application.getInventory();
    }
    private OwnershipRepository ownershipRepository(String customerName) {
        return application == null ? new CustomerOwnershipRepository(context.getCustomerLoginService()) : application.ownershipFor(customerName);
    }
    private PaymentTransactionRepository paymentRepository(String customerName) {
        return application == null ? new LedgerPaymentTransactionRepository(context.getPayments()) : application.paymentsFor(customerName);
    }

    private static InventoryView toInventoryView(InventoryListing listing) {
        Car car = listing.getCar();
        return new InventoryView(listing.getId(), car.getCarMake(), car.getCarModel(), car.getCarYear(),
                listing.getPrice(), listing.getStockQuantity(), listing.isActive(), listing.isAvailable());
    }
    private static PurchaseRequestView toRequestView(PurchaseRequest request) {
        return new PurchaseRequestView(request.getId(), request.getListingId(), request.getCustomerName(),
                request.getStatus(), request.getContractId().orElse(null), request.getPaymentMonths(), request.getMonthlyPayment());
    }
    private static <T> List<T> immutable(List<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }

    public static final class AccountView {
        private final String username; private final AccountRole role;
        AccountView(String username,AccountRole role){this.username=username;this.role=role;}
        public String getUsername(){return username;} public AccountRole getRole(){return role;}
    }
    public static final class InventoryView {
        private final int id; private final String make; private final String model; private final int year; private final int price; private final int stockQuantity; private final boolean active; private final boolean available;
        InventoryView(int id,String make,String model,int year,int price,int stockQuantity,boolean active,boolean available){this.id=id;this.make=make;this.model=model;this.year=year;this.price=price;this.stockQuantity=stockQuantity;this.active=active;this.available=available;}
        public int getId(){return id;} public String getMake(){return make;} public String getModel(){return model;} public int getYear(){return year;} public int getPrice(){return price;} public int getStockQuantity(){return stockQuantity;} public boolean isActive(){return active;} public boolean isAvailable(){return available;}
    }
    public static final class PurchaseRequestView {
        private final int id; private final int listingId; private final String customerName; private final PurchaseRequestStatus status; private final String contractId; private final int paymentMonths; private final int monthlyPayment;
        PurchaseRequestView(int id,int listingId,String customerName,PurchaseRequestStatus status,String contractId,int paymentMonths,int monthlyPayment){this.id=id;this.listingId=listingId;this.customerName=customerName;this.status=status;this.contractId=contractId;this.paymentMonths=paymentMonths;this.monthlyPayment=monthlyPayment;}
        public int getId(){return id;} public int getListingId(){return listingId;} public String getCustomerName(){return customerName;} public PurchaseRequestStatus getStatus(){return status;} public Optional<String> getContractId(){return Optional.ofNullable(contractId);} public int getPaymentMonths(){return paymentMonths;} public int getMonthlyPayment(){return monthlyPayment;}
    }
    public static final class OwnershipView {
        private final long ownershipId; private final String contractId; private final String make; private final String model; private final int year; private final int purchasePrice; private final int amountPaid; private final int remainingBalance; private final int monthlyPayment; private final int termMonths;
        OwnershipView(long ownershipId,String contractId,String make,String model,int year,int purchasePrice,int amountPaid,int remainingBalance,int monthlyPayment,int termMonths){this.ownershipId=ownershipId;this.contractId=contractId;this.make=make;this.model=model;this.year=year;this.purchasePrice=purchasePrice;this.amountPaid=amountPaid;this.remainingBalance=remainingBalance;this.monthlyPayment=monthlyPayment;this.termMonths=termMonths;}
        public long getOwnershipId(){return ownershipId;} public Optional<String> getContractId(){return Optional.ofNullable(contractId);} public String getMake(){return make;} public String getModel(){return model;} public int getYear(){return year;} public int getPurchasePrice(){return purchasePrice;} public int getAmountPaid(){return amountPaid;} public int getRemainingBalance(){return remainingBalance;} public int getMonthlyPayment(){return monthlyPayment;} public int getTermMonths(){return termMonths;}
    }
    public static final class PaymentTransactionView {
        private final String transactionId; private final String customerName; private final Long ownershipId; private final String contractId; private final int amount; private final int totalPaid; private final int remainingBalance; private final Instant recordedAt;
        PaymentTransactionView(String transactionId,String customerName,Long ownershipId,String contractId,int amount,int totalPaid,int remainingBalance,Instant recordedAt){this.transactionId=transactionId;this.customerName=customerName;this.ownershipId=ownershipId;this.contractId=contractId;this.amount=amount;this.totalPaid=totalPaid;this.remainingBalance=remainingBalance;this.recordedAt=recordedAt;}
        public String getTransactionId(){return transactionId;} public String getCustomerName(){return customerName;} public OptionalLong getOwnershipId(){return ownershipId==null?OptionalLong.empty():OptionalLong.of(ownershipId);} public Optional<String> getContractId(){return Optional.ofNullable(contractId);} public int getAmount(){return amount;} public int getTotalPaid(){return totalPaid;} public int getRemainingBalance(){return remainingBalance;} public Instant getRecordedAt(){return recordedAt;}
    }
    public static final class PaymentReportView {
        private final int amountOwed; private final int totalPaid; private final int balance; private final List<PaymentTransactionView> transactions;
        PaymentReportView(int amountOwed,int totalPaid,int balance,List<PaymentTransactionView> transactions){this.amountOwed=amountOwed;this.totalPaid=totalPaid;this.balance=balance;this.transactions=transactions;}
        public int getAmountOwed(){return amountOwed;} public int getTotalPaid(){return totalPaid;} public int getBalance(){return balance;} public List<PaymentTransactionView> getTransactions(){return transactions;}
    }
}
