package dev.comfybatch.domain;

public record Task(
        String id,
        String jobId,
        int ordinalNo,
        TaskState state,
        String workflow,
        long seed,
        String promptId,
        int attempt,
        int progress,
        String error,
        long createdAt,
        long updatedAt,
        Long startedAt,
        long nextRunAt,
        boolean cancelRequested) {}
