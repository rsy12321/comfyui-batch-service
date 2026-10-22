package dev.comfybatch.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.comfybatch.api.ApiModels.*;
import dev.comfybatch.comfy.ComfyMessage;
import dev.comfybatch.domain.*;
import dev.comfybatch.support.Json;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class JobScenarioTest extends ScenarioSupport {
    @Test
    void pendingTaskOnlyAcceptsLegalTransitionsAndRecordsEvents() {
        // given
        String taskId = task();
        // when
        assertThrows(
                IllegalStateException.class,
                () -> lifecycle.transition(taskId, TaskState.SUCCEEDED, "bad"));
        lifecycle.transition(taskId, TaskState.SUBMITTED, "submitted");
        lifecycle.transition(taskId, TaskState.RUNNING, "running");
        lifecycle.transition(taskId, TaskState.SUCCEEDED, "done");
        // then
        assertEquals(4, events.findByTask(taskId).size());
        assertTrue(TaskState.PENDING.permits(TaskState.SUBMITTED));
        assertFalse(TaskState.PENDING.permits(TaskState.SUCCEEDED));
        for (TaskState terminal :
                List.of(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED))
            for (TaskState next : TaskState.values()) assertFalse(terminal.permits(next));
    }

    @Test
    void sameIdempotencyKeyReplaysButDifferentContentConflicts() {
        // given
        String templateId = jobs.template(template()).id();
        var request = new SubmitRequest(templateId, Map.of(), 5, List.of(1L, 2L, 3L, 4L, 5L));
        // when
        var first = jobs.submit(request, "same");
        var repeat = jobs.submit(request, "same");
        // then
        assertEquals(first.id(), repeat.id());
        assertTrue(repeat.replayed());
        assertEquals(5, data.taskCount());
        assertThrows(
                ResponseStatusException.class,
                () -> jobs.submit(new SubmitRequest(templateId, Map.of(), 1, List.of(3L)), "same"));
    }

    @Test
    void fourthFailedAttemptExhaustsExactlyThreeRetries() {
        // given
        String taskId = task();
        lifecycle.transition(taskId, TaskState.SUBMITTED, "test");
        // when / then: initial attempt plus three retries
        for (int i = 1; i <= 4; i++) {
            data.setAttempt(taskId, i);
            failures.handleFailure(taskId, new Exception("node_errors: invalid node"));
            assertEquals(
                    i == 4 ? TaskState.FAILED : TaskState.SUBMITTED, tasks.require(taskId).state());
        }
        assertEquals(1000, TaskFailureHandler.backoff(1000, 1));
        assertEquals(2000, TaskFailureHandler.backoff(1000, 2));
        assertEquals(4000, TaskFailureHandler.backoff(1000, 3));
        assertTrue(tasks.require(taskId).error().contains("node_errors"));
    }

    @Test
    void websocketEventsUpdateOnlyTheMatchingPrompt() {
        // given
        String taskId = task();
        lifecycle.transition(taskId, TaskState.SUBMITTED, "test");
        data.setPrompt(taskId, "own");
        // when / then
        worker.onMessage(taskId, new ComfyMessage("progress", "foreign", 8, 10, false));
        assertEquals(0, tasks.require(taskId).progress());
        worker.onMessage(taskId, new ComfyMessage("executing", "own", 0, 0, false));
        assertEquals(TaskState.RUNNING, tasks.require(taskId).state());
        worker.onMessage(taskId, new ComfyMessage("progress", "own", 5, 20, false));
        assertEquals(25, tasks.require(taskId).progress());
        worker.onMessage(taskId, new ComfyMessage("executed", "own", 0, 0, false));
        assertEquals(99, tasks.require(taskId).progress());
    }

    @Test
    void cancellationIsTerminalEvenWhenLateFailureArrives() {
        // given
        String taskId = task();
        // when
        lifecycle.cancel(taskId);
        lifecycle.cancel(taskId);
        failures.handleFailure(taskId, new Exception("late"));
        // then
        assertEquals(TaskState.CANCELLED, tasks.require(taskId).state());
    }

    @Test
    void invalidDimensionsRollBackTheWholeBatch() {
        // given
        String templateId = jobs.template(template()).id();
        var request = new SubmitRequest(templateId, Map.of("width", Json.read("63")), 2, null);
        // when
        assertThrows(ResponseStatusException.class, () -> jobs.submit(request, null));
        // then
        assertEquals(0, data.jobCount());
    }

    @Test
    void explicitSeedIsWrittenToMappedWorkflowInput() {
        // given
        String taskId = task();
        // when
        Task stored = tasks.require(taskId);
        // then
        assertEquals(
                42, Json.read(stored.workflow()).path("1").path("inputs").path("seed").asLong());
    }
}
