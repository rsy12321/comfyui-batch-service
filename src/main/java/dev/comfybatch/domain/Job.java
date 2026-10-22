package dev.comfybatch.domain;

public record Job(
        String id, String templateId, String idempotencyKey, String requestHash, long createdAt) {}
