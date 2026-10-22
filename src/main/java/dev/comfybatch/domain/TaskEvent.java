package dev.comfybatch.domain;

public record TaskEvent(
        String id,
        String taskId,
        String fromState,
        String toState,
        String reason,
        long happenedAt) {}
