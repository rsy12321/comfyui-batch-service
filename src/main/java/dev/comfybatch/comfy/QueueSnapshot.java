package dev.comfybatch.comfy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public record QueueSnapshot(List<String> running, List<String> pending) {
    public boolean contains(String promptId) {
        return running.contains(promptId) || pending.contains(promptId);
    }

    public static QueueSnapshot from(JsonNode queue) {
        return new QueueSnapshot(
                ids(queue.path("queue_running")), ids(queue.path("queue_pending")));
    }

    private static List<String> ids(JsonNode entries) {
        var identifiers = new ArrayList<String>();
        entries.forEach(entry -> identifiers.add(entry.path(1).asText()));
        return identifiers;
    }
}
