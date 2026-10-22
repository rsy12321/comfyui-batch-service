package dev.comfybatch.domain;

public record ImageResult(
        String id, String taskId, String path, String sha256, int width, int height, long seed) {}
