package dev.comfybatch.domain;

public record StoredImage(String path, String sha256, int width, int height) {}
