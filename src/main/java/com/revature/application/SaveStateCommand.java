package com.revature.application;

import java.util.Objects;

import com.revature.DAOService.SaveResult;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;

public final class SaveStateCommand {
    private final ApplicationPorts.Persistence storage;

    public SaveStateCommand(DealershipApplicationContext context) {
        this(new ContextPersistenceAdapter(context));
    }

    public SaveStateCommand(ApplicationPorts.Persistence storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** Legacy no-actor entry point intentionally fails closed. */
    public SaveResult execute(String filename) {
        throw new SecurityException("verified administrator required to save state");
    }

    public SaveResult execute(User actor, String filename) {
        return storage.save(actor, storage.snapshotSource(actor), filename);
    }
}