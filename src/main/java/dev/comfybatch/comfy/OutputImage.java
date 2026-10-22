package dev.comfybatch.comfy;

import com.fasterxml.jackson.databind.JsonNode;

public record OutputImage(String filename, String subfolder, String type) {
    public static OutputImage from(JsonNode image) {
        return new OutputImage(
                image.path("filename").asText(),
                image.path("subfolder").asText(),
                image.path("type").asText());
    }
}
