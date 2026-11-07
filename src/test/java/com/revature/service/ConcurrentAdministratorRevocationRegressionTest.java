package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.support.TestAccounts;

/**
 * Wave 16: deterministic post-authorization revocation regressions.
 * Expected to expose a missing atomic actor-revalidation boundary in Stage 28.
 * TESTS ONLY; no production users, credentials, or databases.
 */
@Tag("SECURITY")
@Tag("TARGET-BEHAVIOR")
class ConcurrentAdministratorRevocationRegressionTest {
    private enum Operation { PROVISION, PROMOTE, DELETE }

    @Test void memoryProvisionRejectsRevokedAdministrator() throws Exception {
        verifyRevocation(new InMemoryUserAccountRepository(), Operation.PROVISION);
    }
    @Test void jdbcProvisionRejectsRevokedAdministrator() throws Exception {
        verifyRevocation(new JdbcUserAccountRepository(
                JdbcDatabase.inMemory("wave16_provision_" + System.nanoTime())), Operation.PROVISION);
    }
    @Test void memoryPromotionRejectsRevokedAdministrator() throws Exception {
        verifyRevocation(new InMemoryUserAccountRepository(), Operation.PROMOTE);
    }
    @Test void jdbcPromotionRejectsRevokedAdministrator() throws Exception {
        verifyRevocation(new JdbcUserAccountRepository(
                JdbcDatabase.inMemory("wave16_promote_" + System.nanoTime())), Operation.PROMOTE);
    }
    @Test void memoryDeletionRejectsRevokedAdministrator() throws Exception {
        verifyRevocation(new InMemoryUserAccountRepository(), Operation.DELETE);
    }
    @Test void jdbcDeletionRejectsRevokedAdministrator() throws Exception {
        verifyRevocation(new JdbcUserAccountRepository(
                JdbcDatabase.inMemory("wave16_delete_" + System.nanoTime())), Operation.DELETE);
    }

    private static void verifyRevocation(UserAccountRepository raw, Operation operation)
            throws Exception {
        TestAccounts.seed(raw, AccountRole.ADMINISTRATOR, "root", "root-password");
        TestAccounts.seed(raw, AccountRole.ADMINISTRATOR, "backup", "backup-password");
        TestAccounts.seed(raw, AccountRole.CUSTOMER, "buyer", "buyer-password");
        BarrierRepository gated = new BarrierRepository(raw, operation);
        UserLoginService service = new UserLoginService(gated, gated);
        User admin = new User();
        admin.setUsername("root");
        admin.setPassword("root-password");
        admin.setRole(AccountRole.ADMINISTRATOR);

        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> attempted = workers.submit(() -> {
                switch (operation) {
                    case PROVISION:
                        service.provisionUser(admin, "newEmployee", "new-password", AccountRole.EMPLOYEE);
                        return;
                    case PROMOTE:
                        service.changeRole(admin, "buyer", AccountRole.CUSTOMER, AccountRole.EMPLOYEE);
                        return;
                    case DELETE:
                        service.removeUser(admin, "buyer");
                        return;
                    default:
                        throw new AssertionError("Unsupported test operation");
                }
            });

            assertTrue(gated.reachedWriter.await(15, TimeUnit.SECONDS),
                    "must reach privileged writer after initial authorization");
            assertTrue(raw.updateRoleIfCurrent("root", AccountRole.ADMINISTRATOR,
                    AccountRole.CUSTOMER), "test fixture must revoke actor");
            gated.releaseWriter.countDown();

            try {
                attempted.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof SecurityException)) throw failure;
            }

            switch (operation) {
                case PROVISION:
                    assertFalse(raw.findByUsername("newEmployee").isPresent(),
                            "revoked admin must not provision privileged user");
                    break;
                case PROMOTE:
                    assertEquals(AccountRole.CUSTOMER, raw.findByUsername("buyer")
                            .orElseThrow(AssertionError::new).getRole(),
                            "revoked admin must not elevate another user");
                    break;
                case DELETE:
                    assertTrue(raw.findByUsername("buyer").isPresent(),
                            "revoked admin must not delete another user");
                    break;
                default:
                    throw new AssertionError("Unsupported test operation");
            }
        } finally {
            gated.releaseWriter.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private static final class BarrierRepository implements UserAccountRepository, AuthorizedAdminAccountWriter {
        private final UserAccountRepository delegate;
        private final Operation operation;
        final CountDownLatch reachedWriter = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);

        BarrierRepository(UserAccountRepository delegate, Operation operation) {
            this.delegate = delegate;
            this.operation = operation;
        }

        private void pause() {
            reachedWriter.countDown();
            try {
                if (!releaseWriter.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for deterministic barrier");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting at writer", e);
            }
        }


        /**
         * Pause before entering the *trusted atomic operation*, after the
         * service has authenticated the acting user. Revocation must be
         * observed by that operation, not merely at the service-level check.
         */
        private AuthorizedAdminAccountWriter trusted() {
            if (!(delegate instanceof AuthorizedAdminAccountWriter)) {
                throw new AssertionError("test repository lacks trusted administration port");
            }
            return (AuthorizedAdminAccountWriter) delegate;
        }

        @Override public User provisionAuthorized(User actor, String plaintext, User newUser) {
            if (operation == Operation.PROVISION && "newEmployee".equals(newUser.getUsername())) {
                pause();
            }
            return trusted().provisionAuthorized(actor, plaintext, newUser);
        }

        @Override public boolean changeRoleAuthorized(User actor, String plaintext,
                String target, AccountRole expected, AccountRole replacement) {
            if (operation == Operation.PROMOTE && "buyer".equals(target)) pause();
            return trusted().changeRoleAuthorized(actor, plaintext, target, expected, replacement);
        }

        @Override public boolean removeAuthorized(User actor, String plaintext, String target) {
            if (operation == Operation.DELETE && "buyer".equals(target)) pause();
            return trusted().removeAuthorized(actor, plaintext, target);
        }

        @Override public User save(User user) {
            if (operation == Operation.PROVISION && "newEmployee".equals(user.getUsername()))
                pause();
            return delegate.save(user);
        }
        @Override public boolean updateRoleIfCurrent(
                String username, AccountRole expected, AccountRole replacement) {
            if (operation == Operation.PROMOTE && "buyer".equals(username)) pause();
            return delegate.updateRoleIfCurrent(username, expected, replacement);
        }
        @Override public boolean removeByUsername(String username) {
            if (operation == Operation.DELETE && "buyer".equals(username)) pause();
            return delegate.removeByUsername(username);
        }
        @Override public boolean updateCredentialIfCurrent(String username,
                String expected, String replacement) {
            return delegate.updateCredentialIfCurrent(username, expected, replacement);
        }
        @Override public Optional<User> findByUsername(String username) {
            return delegate.findByUsername(username);
        }
        @Override public List<User> findAll() {
            return delegate.findAll();
        }
    }
}
