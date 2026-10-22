package dev.comfybatch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.comfybatch.api.ApiModels.*;
import dev.comfybatch.domain.ParameterMapping;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class WorkflowParameters {
    private WorkflowParameters() {}

    public static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static void validateTemplate(TemplateRequest request) {
        if (request.name() == null
                || request.name().isBlank()
                || request.name().length() > 200
                || request.workflow() == null
                || !request.workflow().isObject()
                || request.mappings() == null) throw invalid("Invalid template");
        for (String name : List.of("prompt", "negative_prompt", "seed", "width", "height", "steps"))
            if (!request.mappings().containsKey(name)) throw invalid("Missing mapping: " + name);
        request.mappings()
                .forEach((name, mapping) -> validateMapping(request.workflow(), name, mapping));
    }

    private static void validateMapping(JsonNode workflow, String name, ParameterMapping mapping) {
        if (mapping == null
                || mapping.nodeId() == null
                || mapping.inputKey() == null
                || !workflow.path(mapping.nodeId()).path("inputs").has(mapping.inputKey()))
            throw invalid("Invalid mapping: " + name);
    }

    public static void validateRequest(SubmitRequest request, String key) {
        if (request.count() < 1
                || request.count() > 100
                || request.parameters() == null
                || request.templateId() == null)
            throw invalid("count must be 1..100 and parameters required");
        if (key != null && (key.isBlank() || key.length() > 128))
            throw invalid("Invalid Idempotency-Key");
        if (request.seeds() != null
                && (request.seeds().size() != request.count()
                        || request.seeds().stream().anyMatch(seed -> seed == null || seed < 0)))
            throw invalid("seeds must contain count nonnegative longs");
    }

    public static void validateValue(String name, JsonNode value) {
        if (value == null || value.isNull()) throw invalid("Null parameter");
        if (Set.of("prompt", "negative_prompt").contains(name)
                && (!value.isTextual() || value.textValue().length() > 10000))
            throw invalid("Invalid text");
        if (Set.of("width", "height", "steps", "seed").contains(name)
                && (!value.isIntegralNumber()
                        || !value.canConvertToLong()
                        || value.longValue() < 0)) throw invalid("Invalid numeric parameter");
        if (Set.of("width", "height").contains(name)
                && (value.longValue() < 64
                        || value.longValue() > 2048
                        || value.longValue() % 8 != 0))
            throw invalid("Dimensions must be multiples of 8 in 64..2048");
        if (name.equals("steps") && (value.longValue() < 1 || value.longValue() > 100))
            throw invalid("steps must be 1..100");
    }

    public static JsonNode apply(
            JsonNode workflow, JsonNode mappings, Map<String, JsonNode> parameters) {
        for (String name : parameters.keySet())
            if (!mappings.has(name)) throw invalid("Unknown parameter: " + name);
        parameters.forEach(
                (name, value) -> {
                    validateValue(name, value);
                    var mapping = mappings.get(name);
                    ((ObjectNode) workflow.path(mapping.path("nodeId").asText()).path("inputs"))
                            .set(mapping.path("inputKey").asText(), value);
                });
        return workflow;
    }

    public static JsonNode withSeed(JsonNode workflow, JsonNode mappings, long seed) {
        JsonNode copy = workflow.deepCopy();
        var mapping = mappings.get("seed");
        ((ObjectNode) copy.path(mapping.path("nodeId").asText()).path("inputs"))
                .put(mapping.path("inputKey").asText(), seed);
        return copy;
    }
}
