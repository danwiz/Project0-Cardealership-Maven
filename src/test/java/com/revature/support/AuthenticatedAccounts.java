package com.revature.support;

import com.revature.application.AuthenticateAccountCommand;
import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.ConfiguredDomainAdapter;
import com.revature.application.ContextIdentityAdapter;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;
import com.revature.service.Permission;

/** Test-only login helper: obtains a verified actor through the actual credential boundary. */
public final class AuthenticatedAccounts {
    private AuthenticatedAccounts() { }

    public static User login(DealershipApplicationContext context, AccountRole role,
            String username, String password) {
        return new AuthenticateAccountCommand(context).execute(
                role, credentials(username, password), Permission.VIEW_INVENTORY)
                .getAccount().orElseThrow(() -> new AssertionError("test login failed for " + username));
    }

    public static User login(ConfiguredDealershipApplication application, AccountRole role,
            String username, String password) {
        return new AuthenticateAccountCommand(
                new ContextIdentityAdapter(application.getContext()),
                new ConfiguredDomainAdapter(application))
                .execute(role, credentials(username, password), Permission.VIEW_INVENTORY)
                .getAccount().orElseThrow(() -> new AssertionError("test login failed for " + username));
    }

    private static User credentials(String username, String password) {
        User credentials = new User();
        credentials.setUsername(username);
        credentials.setPassword(password);
        return credentials;
    }
}
