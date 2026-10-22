package dev.comfybatch.support;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    public static JsonNode read(String source) {
        try {
            return MAPPER.readTree(source);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var result = MAPPER.createObjectNode();
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, sorted(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode();
            node.forEach(child -> result.add(sorted(child)));
            return result;
        }
        return node;
    }

    public static String hashRequest(Object value) {
        return hash(sorted(MAPPER.valueToTree(value)).toString().getBytes(StandardCharsets.UTF_8));
    }
}
