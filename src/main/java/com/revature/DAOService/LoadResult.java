package com.revature.DAOService;

import java.util.Optional;

import com.revature.cardealer.ApplicationStateSnapshot;

public final class LoadResult {

    public enum Status {
        SUCCESS,
        NOT_FOUND,
        INVALID_CONTENT,
        UNSUPPORTED_VERSION,
        IO_ERROR,
        CLASS_NOT_FOUND
    }

    private final Status status;
    private final ApplicationStateSnapshot snapshot;
    private final String message;

    private LoadResult(Status status, ApplicationStateSnapshot snapshot, String message) {
        this.status = status;
        this.snapshot = snapshot;
        this.message = message;
    }

    public static LoadResult success(ApplicationStateSnapshot snapshot) {
        return new LoadResult(Status.SUCCESS, snapshot, "state loaded");
    }

    public static LoadResult invalidContent(String message) {
        return failure(Status.INVALID_CONTENT, message);
    }

    public static LoadResult failure(Status status, String message) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("failure status must not be SUCCESS");
        }
        return new LoadResult(status, null, message);
    }

    public Status getStatus() {
        return status;
    }

    public Optional<ApplicationStateSnapshot> getSnapshot() {
        return Optional.ofNullable(snapshot);
    }

    public String getMessage() {
        return message;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}