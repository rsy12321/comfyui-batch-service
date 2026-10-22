package dev.comfybatch.comfy;

import com.fasterxml.jackson.databind.JsonNode;

public record ComfyMessage(String type, String promptId, int value, int max, boolean nodeIsNull) {
    public static ComfyMessage from(JsonNode message) {
        var data = message.path("data");
        return new ComfyMessage(
                message.path("type").asText(),
                data.path("prompt_id").asText(),
                data.path("value").asInt(),
                data.path("max").asInt(),
                data.path("node").isNull());
    }
}
