package dev.comfybatch.domain;

public record TaskAttempt(
        String id,
        String taskId,
        int attemptNo,
        String promptId,
        String error,
        long startedAt,
        Long endedAt) {}
