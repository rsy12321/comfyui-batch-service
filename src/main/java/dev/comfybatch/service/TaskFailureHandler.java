package dev.comfybatch.service;

import dev.comfybatch.config.AppProperties;
import dev.comfybatch.domain.Task;
import dev.comfybatch.domain.TaskState;
import dev.comfybatch.repository.TaskAttemptRepository;
import dev.comfybatch.repository.TaskRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskFailureHandler {
    private final TaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final TaskLifecycle lifecycle;
    private final TransactionTemplate transactions;
    private final AppProperties properties;

    public TaskFailureHandler(
            TaskRepository tasks,
            TaskAttemptRepository attempts,
            TaskLifecycle lifecycle,
            TransactionTemplate transactions,
            AppProperties properties) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.lifecycle = lifecycle;
        this.transactions = transactions;
        this.properties = properties;
    }

    public static long backoff(long base, int attempt) {
        return base * (1L << Math.min(10, Math.max(0, attempt - 1)));
    }

    public void defer(String taskId, String reason) {
        synchronized (lifecycle) {
            Task task = tasks.require(taskId);
            if (task.state().terminal()) return;
            tasks.defer(
                    taskId,
                    System.currentTimeMillis() + Math.max(1000, properties.pollMs()),
                    reason);
            lifecycle.event(taskId, task.state().name(), task.state().name(), reason);
        }
    }

    public void handleFailure(String taskId, Exception error) {
        synchronized (lifecycle) {
            Task task = tasks.require(taskId);
            if (task.state().terminal()) return;
            String description =
                    error.getClass().getSimpleName()
                            + ": "
                            + Objects.toString(error.getMessage(), "unknown");
            String message =
                    description.length() > 30000 ? description.substring(0, 30000) : description;
            long now = System.currentTimeMillis();
            transactions.executeWithoutResult(
                    transaction -> {
                        attempts.fail(taskId, task.attempt(), message, now);
                        tasks.setError(taskId, message, now);
                        scheduleOrFinish(task, message, now);
                    });
        }
    }

    private void scheduleOrFinish(Task task, String message, long now) {
        if (task.cancelRequested()) {
            lifecycle.event(
                    task.id(),
                    task.state().name(),
                    task.state().name(),
                    "Cancellation pending: " + message);
            tasks.setNextRun(task.id(), now + backoff(properties.retryBaseMs(), 1));
        } else if (task.attempt() >= properties.maxAttempts()) {
            lifecycle.transition(
                    task.id(),
                    TaskState.FAILED,
                    "Max attempts (" + properties.maxAttempts() + ") exhausted: " + message);
        } else {
            tasks.scheduleRetry(
                    task.id(),
                    now + backoff(properties.retryBaseMs(), Math.max(1, task.attempt())));
            lifecycle.event(
                    task.id(),
                    task.state().name(),
                    task.state().name(),
                    "Retry scheduled: " + message);
        }
    }
}
