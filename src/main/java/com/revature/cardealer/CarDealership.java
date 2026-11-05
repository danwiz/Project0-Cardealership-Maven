package com.revature.cardealer;

import java.util.Scanner;

import com.revature.DAOService.LoadResult;
import com.revature.DAOService.SaveResult;
import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.service.CustomerLoginService;

/**
 * Backward-compatible console entry point. Mutable domain and service state lives
 * in {@link DealershipApplicationContext}; menu behavior lives in
 * {@link ConsoleCommandHandler}.
 */
public class CarDealership {

    private static ConfiguredDealershipApplication application = DealershipCompositionRoot.createDefault();
    private static DealershipApplicationContext context = application.getContext();
    private static boolean legacyContextOverride;
    private static Scanner scan = new Scanner(System.in);

    public static void main(String[] args) {
        handler().run();
    }

    private static ConsoleCommandHandler handler() {
        ScannerConsoleIO io = new ScannerConsoleIO(scan, System.out);
        return legacyContextOverride ? new ConsoleCommandHandler(context, io)
                : new ConsoleCommandHandler(application, io);
    }

    /** Compatibility seam retained for the existing characterization suite. */
    private static void performUserAction(String option) {
        handler().handleMainCommand(option);
    }

    public static User getUserInfo() {
        return handler().readCredentials();
    }

    static User getCurrentAccount() { return context.getCurrentAccount(); }
    static CustomerLoginService getCustomerService() { return context.getCustomerLoginService(); }
    static Offer getInventory() { return context.getInventory(); }
    static Payments getPaymentLedger() { return context.getPayments(); }
    static DealershipApplicationContext getApplicationContext() { return context; }

    static void apply(RehydratedApplicationState replacement) {
        context.replaceRuntime(replacement);
    }

    static void replaceApplicationContext(DealershipApplicationContext replacement) {
        context = replacement;
        legacyContextOverride = true;
    }

    static void replaceConfiguredApplication(ConfiguredDealershipApplication replacement) {
        application = replacement;
        context = replacement.getContext();
        legacyContextOverride = false;
    }

    public LoadResult loadData() { return handler().loadData(); }
    public SaveResult saveData() { return handler().saveData(); }
    public LoadResult loadData(User actor) { return handler().loadData(actor); }
    public SaveResult saveData(User actor) { return handler().saveData(actor); }
}