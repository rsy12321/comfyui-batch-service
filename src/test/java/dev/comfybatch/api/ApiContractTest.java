package dev.comfybatch.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.comfybatch.api.ApiModels.*;
import dev.comfybatch.service.ScenarioSupport;
import dev.comfybatch.support.Json;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ApiContractTest extends ScenarioSupport {
    @Autowired MockMvc mvc;
    private static final String KEY = "test-key-not-secret";

    private static final Set<String> TASK_DETAIL_FIELDS =
            Set.of(
                    "id",
                    "job_id",
                    "ordinal_no",
                    "state",
                    "seed",
                    "prompt_id",
                    "attempt",
                    "progress",
                    "error",
                    "created_at",
                    "updated_at",
                    "started_at",
                    "next_run_at",
                    "cancel_requested",
                    "events",
                    "attempts",
                    "results");

    @Test
    void taskDetailsKeepV1FlatSnakeCaseFields() throws Exception {
        // given
        String taskId = task();
        // when
        String response =
                mvc.perform(get("/api/tasks/" + taskId).header("X-API-Key", KEY))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        // then
        var json = Json.read(response);
        var names = new TreeSet<String>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(TASK_DETAIL_FIELDS, names);
        assertTrue(json.path("prompt_id").isNull());
        assertEquals("PENDING", json.path("state").asText());
        assertTrue(json.path("events").get(0).has("happened_at"));
    }

    @Test
    void jobSummaryKeepsV1NamesAndOmitsWorkflow() throws Exception {
        // given
        String taskId = task();
        String jobId = tasks.require(taskId).jobId();
        // when
        var response =
                mvc.perform(get("/api/jobs/" + jobId).header("X-API-Key", KEY))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        // then
        var json = Json.read(response);
        var names = new TreeSet<String>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("id", "template_id", "created_at", "tasks", "state"), names);
        assertTrue(json.path("tasks").get(0).has("ordinal_no"));
        assertFalse(json.path("tasks").get(0).has("workflow"));
    }

    @Test
    void missingApiKeyStillReturns401WithStructuredError() throws Exception {
        // given / when
        var result = mvc.perform(get("/api/jobs"));
        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Unauthorized"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void missingTaskStillReturns404WithStructuredError() throws Exception {
        // given / when
        var result = mvc.perform(get("/api/tasks/missing").header("X-API-Key", KEY));
        // then
        result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void conflictingIdempotencyKeyStillReturns409() throws Exception {
        // given
        String templateId = jobs.template(template()).id();
        jobs.submit(new SubmitRequest(templateId, Map.of(), 1, List.of(1L)), "key");
        // when
        var result =
                mvc.perform(
                        post("/api/jobs")
                                .header("X-API-Key", KEY)
                                .header("Idempotency-Key", "key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        Json.write(
                                                new SubmitRequest(
                                                        templateId, Map.of(), 1, List.of(2L)))));
        // then
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void validationErrorRetainsReasonWithoutInternalStack() throws Exception {
        // given
        String templateId = jobs.template(template()).id();
        var request = new SubmitRequest(templateId, Map.of(), 0, null);
        // when
        var result =
                mvc.perform(
                        post("/api/jobs")
                                .header("X-API-Key", KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(Json.write(request)));
        // then
        result.andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message").value("count must be 1..100 and parameters required"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void databaseFailureDoesNotExposeSqlOrStack() {
        // given
        var handler = new ApiExceptionHandler();
        var failure = new DataIntegrityViolationException("private SQL detail");
        // when
        var response = handler.unexpected(failure);
        // then
        assertEquals(500, response.getStatusCode().value());
        assertFalse(Json.write(response.getBody()).contains("private SQL detail"));
        assertEquals(List.of("Database operation failed"), response.getBody().details());
    }

    @Test
    void unknownRouteStillReturns404() throws Exception {
        // given / when
        var result = mvc.perform(get("/api/no-such-route").header("X-API-Key", KEY));
        // then
        result.andExpect(status().isNotFound());
    }
}
