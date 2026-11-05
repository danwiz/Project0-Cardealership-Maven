package com.revature.DAOService;

public final class SaveResult {

    public enum Status {
        SUCCESS,
        INVALID_INPUT,
        IO_ERROR
    }

    private final Status status;
    private final String filename;
    private final String message;

    private SaveResult(Status status, String filename, String message) {
        this.status = status;
        this.filename = filename;
        this.message = message;
    }

    public static SaveResult success(String filename) {
        return new SaveResult(Status.SUCCESS, filename, "state saved");
    }

    public static SaveResult invalidInput(String message) {
        return failure(Status.INVALID_INPUT, null, message);
    }

    public static SaveResult failure(Status status, String filename, String message) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("failure status must not be SUCCESS");
        }
        return new SaveResult(status, filename, message);
    }

    public Status getStatus() {
        return status;
    }

    public String getFilename() {
        return filename;
    }

    public String getMessage() {
        return message;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}