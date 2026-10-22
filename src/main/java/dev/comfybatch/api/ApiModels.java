package dev.comfybatch.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import dev.comfybatch.domain.*;
import java.util.*;

public final class ApiModels {
    private ApiModels() {}

    public record TemplateRequest(
            String name, JsonNode workflow, Map<String, ParameterMapping> mappings) {}

    public record SubmitRequest(
            String templateId, Map<String, JsonNode> parameters, int count, List<Long> seeds) {}

    public record CreatedResponse(String id) {}

    public record SubmissionResponse(String id, boolean replayed) {}

    public record PageResponse(int page, int size, long total, List<JobResponse> items) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TemplateResponse(String id, String name, long createdAt) {
        public static TemplateResponse from(Template template) {
            return new TemplateResponse(template.id(), template.name(), template.createdAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record JobResponse(
            String id,
            String templateId,
            long createdAt,
            List<TaskSummary> tasks,
            TaskState state) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskSummary(
            String id,
            int ordinalNo,
            TaskState state,
            long seed,
            int progress,
            int attempt,
            String promptId,
            String error,
            boolean cancelRequested,
            long createdAt,
            long updatedAt) {
        public static TaskSummary from(Task task) {
            return new TaskSummary(
                    task.id(),
                    task.ordinalNo(),
                    task.state(),
                    task.seed(),
                    task.progress(),
                    task.attempt(),
                    task.promptId(),
                    task.error(),
                    task.cancelRequested(),
                    task.createdAt(),
                    task.updatedAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskFields(
            String id,
            String jobId,
            int ordinalNo,
            TaskState state,
            long seed,
            String promptId,
            int attempt,
            int progress,
            String error,
            long createdAt,
            long updatedAt,
            Long startedAt,
            long nextRunAt,
            boolean cancelRequested) {
        public static TaskFields from(Task task) {
            return new TaskFields(
                    task.id(),
                    task.jobId(),
                    task.ordinalNo(),
                    task.state(),
                    task.seed(),
                    task.promptId(),
                    task.attempt(),
                    task.progress(),
                    task.error(),
                    task.createdAt(),
                    task.updatedAt(),
                    task.startedAt(),
                    task.nextRunAt(),
                    task.cancelRequested());
        }
    }

    public record TaskResponse(
            @JsonUnwrapped TaskFields task,
            List<EventResponse> events,
            List<AttemptResponse> attempts,
            List<ResultResponse> results) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventResponse(
            String id,
            String taskId,
            String fromState,
            String toState,
            String reason,
            long happenedAt) {
        public static EventResponse from(TaskEvent event) {
            return new EventResponse(
                    event.id(),
                    event.taskId(),
                    event.fromState(),
                    event.toState(),
                    event.reason(),
                    event.happenedAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AttemptResponse(
            String id,
            String taskId,
            int attemptNo,
            String promptId,
            String error,
            long startedAt,
            Long endedAt) {
        public static AttemptResponse from(TaskAttempt attempt) {
            return new AttemptResponse(
                    attempt.id(),
                    attempt.taskId(),
                    attempt.attemptNo(),
                    attempt.promptId(),
                    attempt.error(),
                    attempt.startedAt(),
                    attempt.endedAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResultResponse(
            String id,
            String taskId,
            String path,
            String sha256,
            int width,
            int height,
            long seed) {
        public static ResultResponse from(ImageResult result) {
            return new ResultResponse(
                    result.id(),
                    result.taskId(),
                    result.path(),
                    result.sha256(),
                    result.width(),
                    result.height(),
                    result.seed());
        }
    }

    public record ErrorResponse(String code, String message, List<String> details) {}
}
