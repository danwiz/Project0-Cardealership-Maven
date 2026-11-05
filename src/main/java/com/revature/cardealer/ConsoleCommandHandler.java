package com.revature.cardealer;

import com.revature.DAOService.LoadResult;
import com.revature.DAOService.SaveResult;
import com.revature.application.AuthenticateAccountCommand;
import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.ConfiguredDomainAdapter;
import com.revature.application.ContextIdentityAdapter;
import com.revature.application.DealershipQueryService;
import com.revature.application.InfrastructureConfiguration;
import com.revature.application.LoadStateCommand;
import com.revature.application.ManageInventoryCommand;
import com.revature.application.RecordPaymentCommand;
import com.revature.application.RegisterAccountCommand;
import com.revature.application.RequestPurchaseCommand;
import com.revature.application.ReviewPurchaseRequestCommand;
import com.revature.application.SaveStateCommand;
import com.revature.application.SeedDefaultInventoryCommand;
import com.revature.service.Permission;

/** Translates console input into application command and query invocations. */
public final class ConsoleCommandHandler {
    private static final String STATE_FILE = "CarDealer.dat";

    private final DealershipApplicationContext context;
    private final ConsoleIO io;
    private final RegisterAccountCommand registerAccount;
    private final AuthenticateAccountCommand authenticateAccount;
    private final ManageInventoryCommand manageInventory;
    private final RequestPurchaseCommand requestPurchase;
    private final ReviewPurchaseRequestCommand reviewPurchaseRequest;
    private final RecordPaymentCommand recordPayment;
    private final SaveStateCommand saveState;
    private final LoadStateCommand loadState;
    private final SeedDefaultInventoryCommand seedDefaultInventory;
    private final DealershipQueryService queries;
    private final ConsoleViewRenderer renderer;
    private final boolean jdbcMode;

    public ConsoleCommandHandler(DealershipApplicationContext context, ConsoleIO io) {
        this.context = context;
        this.io = io;
        this.registerAccount = new RegisterAccountCommand(context);
        this.authenticateAccount = new AuthenticateAccountCommand(context);
        this.manageInventory = new ManageInventoryCommand(context);
        this.requestPurchase = new RequestPurchaseCommand(context);
        this.reviewPurchaseRequest = new ReviewPurchaseRequestCommand(context);
        this.recordPayment = null;
        this.saveState = new SaveStateCommand(context);
        this.loadState = new LoadStateCommand(context);
        this.seedDefaultInventory = new SeedDefaultInventoryCommand(context);
        this.queries = new DealershipQueryService(context);
        this.renderer = new ConsoleViewRenderer(io);
        this.jdbcMode = false;
    }

    public ConsoleCommandHandler(ConfiguredDealershipApplication application, ConsoleIO io) {
        this.context = application.getContext();
        this.io = io;
        ContextIdentityAdapter identity = new ContextIdentityAdapter(context);
        ConfiguredDomainAdapter domain = new ConfiguredDomainAdapter(application);
        this.registerAccount = new RegisterAccountCommand(identity);
        this.authenticateAccount = new AuthenticateAccountCommand(identity, domain);
        this.manageInventory = new ManageInventoryCommand(domain, domain);
        this.requestPurchase = new RequestPurchaseCommand(domain, domain);
        this.reviewPurchaseRequest = new ReviewPurchaseRequestCommand(
                domain, domain, domain, application.getPurchaseApproval());
        this.recordPayment = new RecordPaymentCommand(application);
        this.saveState = new SaveStateCommand(context);
        this.loadState = new LoadStateCommand(context);
        this.seedDefaultInventory = new SeedDefaultInventoryCommand(domain);
        this.queries = new DealershipQueryService(application);
        this.renderer = new ConsoleViewRenderer(io);
        this.jdbcMode = application.getConfiguration().getMode() == InfrastructureConfiguration.Mode.JDBC;
    }

    public void run() {
        String option;
        do {
            io.writeLine("Welcome: Car Dealership!!\n");
            io.writeLine("[1] Register User");
            io.writeLine("[2] Customer Login");
            io.writeLine("[3] Employee Login");
            io.writeLine("[4] Exit");
            io.writeLine("[5] Admin features");
            option = io.readLine();
            handleMainCommand(option);
        } while (!"4".equals(option));
    }

    public void handleMainCommand(String option) {
        switch (option) {
        case "1": registerAccount(); break;
        case "2": authenticate(AccountRole.CUSTOMER, Permission.VIEW_INVENTORY, this::showCustomerMenu); break;
        case "3": authenticate(AccountRole.EMPLOYEE, Permission.MANAGE_INVENTORY, this::showEmployeeMenu); break;
        case "4": io.writeLine("goodbye"); break;
        case "5": authenticate(AccountRole.ADMINISTRATOR, Permission.MANAGE_PERSISTENCE, this::showAdminMenu); break;
        default: io.writeLine("did not understand input"); break;
        }
    }

    private void registerAccount() {
        io.writeLine("Welcome: Car Dealership!!\\n");
        io.writeLine("[1] Register Customer");
        io.writeLine("Employee and administrator registration requires verified provisioning.");
        String option = io.readLine();
        if (!"1".equals(option)) {
            io.writeLine("access denied: privileged registration requires administrator");
            return;
        }
        User credentials = readCredentials();
        try {
            User account = registerAccount.execute(AccountRole.CUSTOMER,
                    credentials.getUsername(), credentials.getPassword());
            renderer.account(queries.account(account));
            io.writeLine("Customer registered");
        } catch (IllegalArgumentException exception) {
            io.writeLine("Registration failed: " + exception.getMessage());
        }
    }

    private void authenticate(AccountRole role, Permission permission, AccountConsumer consumer) {
        AuthenticateAccountCommand.Result result = authenticateAccount.execute(role, readCredentials(), permission);
        if (result.getStatus() == AuthenticateAccountCommand.Status.INVALID_CREDENTIALS) io.writeLine("failure");
        else if (result.getStatus() == AuthenticateAccountCommand.Status.ACCESS_DENIED) io.writeLine("access denied");
        else {
            User account = result.getAccount().get();
            renderer.account(queries.account(account));
            consumer.accept(account);
        }
    }

    private void showCustomerMenu(User account) {
        io.writeLine("Welcome To The Dealership: \n[1] View Car Lot\n[2] View Cars Owned\n[3] View Payments\n[4] Make Payment");
        String option = io.readLine();
        if ("1".equals(option)) {
            seedDefaultInventory.execute();
            renderer.inventory(queries.inventory(account));
            try { requestPurchase.execute(account, Integer.parseInt(io.readLine())); }
            catch (IllegalArgumentException exception) { io.writeLine("Invalid listing number"); }
        } else if ("2".equals(option)) renderer.ownership(queries.ownership(account));
        else if ("3".equals(option)) renderer.payments(queries.payments(account));
        else if ("4".equals(option) && recordPayment != null) handlePayment(account, account.getUsername());
        else io.writeLine("did not understand input");
    }

    private void showEmployeeMenu(User account) {
        io.writeLine("Employee View: \n[1] View Car Lot\n[2] View Pending Requests\n[3] View Customer Payments\n[4] Record Customer Payment");
        String option = io.readLine();
        if ("1".equals(option)) handleInventoryCommand(account);
        else if ("2".equals(option)) handlePurchaseReview(account);
        else if ("3".equals(option)) {
            if (jdbcMode) {
                io.writeLine("Enter customer username:");
                try { renderer.payments(queries.payments(account, io.readLine())); }
                catch (IllegalArgumentException exception) { io.writeLine("Unknown customer"); }
            } else renderer.payments(queries.payments(account));
        } else if ("4".equals(option) && recordPayment != null) {
            io.writeLine("Enter customer username:");
            handlePayment(account, io.readLine());
        } else io.writeLine("did not understand input");
    }

    private void handlePayment(User actor, String customerName) {
        try {
            io.writeLine("Enter ownership number:");
            int ownershipIndex = Integer.parseInt(io.readLine());
            io.writeLine("Enter payment amount:");
            int amount = Integer.parseInt(io.readLine());
            PaymentTransaction transaction = recordPayment.execute(actor, customerName, ownershipIndex, amount);
            io.writeLine("Payment recorded: " + transaction.getTransactionId()
                    + " Balance: " + transaction.getRemainingBalance());
        } catch (IllegalArgumentException | SecurityException exception) {
            io.writeLine("Payment failed: " + exception.getMessage());
        }
    }

    private void handleInventoryCommand(User account) {
        renderer.inventory(queries.inventory(account));
        String option = io.readLine();
        try {
            if ("1".equals(option)) {
                String[] input = io.readLine().split("\\s*,\\s*");
                if (input.length != 5) { io.writeLine("Invalid vehicle input"); return; }
                manageInventory.add(account, input[0], input[1], Integer.parseInt(input[2]),
                        Integer.parseInt(input[3]), Integer.parseInt(input[4]));
            } else if ("2".equals(option)) manageInventory.remove(account, Integer.parseInt(io.readLine()));
        } catch (IllegalArgumentException exception) { io.writeLine("Invalid vehicle input"); }
    }

    private void handlePurchaseReview(User account) {
        renderer.pendingRequests(queries.pendingRequests(account));
        try {
            int requestNumber = Integer.parseInt(io.readLine());
            int months = Integer.parseInt(io.readLine());
            String rejectInput = io.readLine();
            boolean rejectOthers = "yes".equalsIgnoreCase(rejectInput) || "y".equalsIgnoreCase(rejectInput);
            reviewPurchaseRequest.approve(account, requestNumber, months, rejectOthers);
        } catch (IllegalArgumentException exception) {
            io.writeLine("\nMessage: Can Not Accept The Payment terms ");
        }
    }

    private void showAdminMenu(User account) {
        context.getAuthorizationService().requireAuthorized(account, Permission.MANAGE_PERSISTENCE);
        if (jdbcMode) {
            io.writeLine("JDBC persistence is active; state is committed transactionally by each command.");
            return;
        }
        io.writeLine("You are now an Admin\n [1] Serialize and Save Data\n [2] Deserialize and Load Data");
        String input = io.readLine();
        if ("1".equals(input)) saveData(account);
        else if ("2".equals(input)) loadData(account);
    }

    public LoadResult loadData() {
        throw new SecurityException("verified administrator required for direct snapshot load");
    }

    public LoadResult loadData(User actor) {
        context.getAuthorizationService().requireAuthorized(actor, Permission.MANAGE_PERSISTENCE);
        if (jdbcMode) return LoadResult.invalidContent("file loading is disabled while JDBC persistence is active");
        LoadResult result = loadState.execute(actor, STATE_FILE);
        if (result.isSuccess()) io.writeLine("Application state loaded and activated.");
        else if (result.getStatus() == LoadResult.Status.INVALID_CONTENT) io.writeLine("Load rejected [INVALID_CONTENT]: " + result.getMessage());
        else io.writeLine("Load failed [" + result.getStatus() + "]: " + result.getMessage());
        return result;
    }

    public SaveResult saveData() {
        throw new SecurityException("verified administrator required for direct snapshot save");
    }

    public SaveResult saveData(User actor) {
        context.getAuthorizationService().requireAuthorized(actor, Permission.MANAGE_PERSISTENCE);
        if (jdbcMode) return SaveResult.invalidInput("file saving is disabled while JDBC persistence is active");
        SaveResult result = saveState.execute(actor, STATE_FILE);
        io.writeLine(result.isSuccess() ? "Application state saved."
                : "Save failed [" + result.getStatus() + "]: " + result.getMessage());
        return result;
    }

    public User readCredentials() {
        User user = new User();
        io.writeLine("Enter username:");
        user.setUsername(io.readLine());
        io.writeLine("Enter password");
        user.setPassword(io.readLine());
        return user;
    }

    private interface AccountConsumer { void accept(User account); }
}
