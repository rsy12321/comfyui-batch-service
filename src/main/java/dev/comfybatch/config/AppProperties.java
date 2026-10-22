package dev.comfybatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String apiKey,
        String comfyUrl,
        int concurrency,
        long timeoutSeconds,
        long retryBaseMs,
        long pollMs,
        String storage,
        boolean workerEnabled) {}
