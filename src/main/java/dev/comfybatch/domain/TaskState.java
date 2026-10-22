package dev.comfybatch.domain;

public enum TaskState {
    PENDING,
    SUBMITTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean permits(TaskState next) {
        if (terminal()) return false;
        return next == CANCELLED
                || next == FAILED
                || (this == PENDING && next == SUBMITTED)
                || (this == SUBMITTED && (next == RUNNING || next == SUCCEEDED))
                || (this == RUNNING && next == SUCCEEDED);
    }
}
