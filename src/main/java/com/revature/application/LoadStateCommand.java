package com.revature.application;

import java.util.Objects;

import com.revature.DAOService.LoadResult;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;

public final class LoadStateCommand {
    private final ApplicationPorts.Persistence storage;

    public LoadStateCommand(DealershipApplicationContext context) {
        this(new ContextPersistenceAdapter(context));
    }

    public LoadStateCommand(ApplicationPorts.Persistence storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** Legacy no-actor entry point intentionally fails closed. */
    public LoadResult execute(String filename) {
        throw new SecurityException("verified administrator required to load state");
    }

    public LoadResult execute(User actor, String filename) {
        LoadResult result = storage.load(actor, filename);
        if (!result.isSuccess()) return result;
        try {
            storage.activateLoadedSnapshot(actor, result);
            return result;
        } catch (IllegalArgumentException exception) {
            return LoadResult.failure(LoadResult.Status.INVALID_CONTENT, exception.getMessage());
        }
    }
}