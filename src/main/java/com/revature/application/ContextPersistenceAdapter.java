package com.revature.application;

import java.util.Objects;

import com.revature.DAOService.LoadResult;
import com.revature.DAOService.SaveResult;
import com.revature.cardealer.Data;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.RehydratedApplicationState;
import com.revature.cardealer.User;
import com.revature.service.Permission;

/** Binds persistence and runtime activation to the current application context. */
public final class ContextPersistenceAdapter implements ApplicationPorts.Persistence {
    private final DealershipApplicationContext context;

    public ContextPersistenceAdapter(DealershipApplicationContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    private static final String APPROVED_SNAPSHOT_FILE = "CarDealer.dat";

    private void requireSnapshotAuthority(User actor, String filename) {
        // A supplied username/role is not proof of authentication.
        if (!context.getAuthorizationService().enforcesVerifiedSessions()) {
            throw new SecurityException("verified session enforcement is required for persistence");
        }
        context.getAuthorizationService().requireAuthorized(actor, Permission.MANAGE_PERSISTENCE);
        // Do not let an in-process adapter turn into an arbitrary file reader/writer.
        if (!APPROVED_SNAPSHOT_FILE.equals(filename)) {
            throw new SecurityException("snapshot path is not authorized");
        }
    }

    private void requireAuthority(User actor) {
        if (!context.getAuthorizationService().enforcesVerifiedSessions()) {
            throw new SecurityException("verified session enforcement is required for persistence");
        }
        context.getAuthorizationService().requireAuthorized(actor, Permission.MANAGE_PERSISTENCE);
    }

    @Override public SaveResult save(User actor, Data source, String filename) {
        requireSnapshotAuthority(actor, filename);
        return context.saveAuthorizedSnapshot(actor, source, filename);
    }

    @Override public LoadResult load(User actor, String filename) {
        requireSnapshotAuthority(actor, filename);
        return context.loadAuthorizedSnapshot(actor, filename);
    }

    @Override public Data snapshotSource(User actor) {
        requireAuthority(actor);
        return context.snapshotSource();
    }

    @Override
    public void activateLoadedSnapshot(User actor, LoadResult result) {
        requireAuthority(actor);
        if (!result.isSuccess() || !result.getSnapshot().isPresent()) {
            throw new IllegalArgumentException("successful load result with snapshot is required");
        }
        RehydratedApplicationState replacement = context.getSnapshotRehydrator()
                .rehydrate(result.getSnapshot().get());
        requireAuthority(actor); // reject a demotion between validation and replacement
        context.replaceRuntime(replacement);
    }
}