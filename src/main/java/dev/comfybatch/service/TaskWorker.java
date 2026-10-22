package dev.comfybatch.service;

import dev.comfybatch.comfy.*;
import dev.comfybatch.config.AppProperties;
import dev.comfybatch.domain.*;
import dev.comfybatch.repository.TaskRepository;
import dev.comfybatch.support.Json;
import jakarta.annotation.PreDestroy;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskWorker {
    private static final Logger LOG = LoggerFactory.getLogger(TaskWorker.class);
    private final TaskRepository tasks;
    private final ComfyClient comfy;
    private final AppProperties properties;
    private final TaskLifecycle lifecycle;
    private final TaskFailureHandler failures;
    private final TaskRecovery recovery;
    private final TaskCompletion completion;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closing;

    public TaskWorker(
            TaskRepository tasks,
            ComfyClient comfy,
            AppProperties properties,
            TaskLifecycle lifecycle,
            TaskFailureHandler failures,
            TaskRecovery recovery,
            TaskCompletion completion) {
        validateConfiguration(properties);
        this.tasks = tasks;
        this.comfy = comfy;
        this.properties = properties;
        this.lifecycle = lifecycle;
        this.failures = failures;
        this.recovery = recovery;
        this.completion = completion;
    }

    private static void validateConfiguration(AppProperties properties) {
        if (properties.concurrency() < 1
                || properties.concurrency() > 16
                || properties.timeoutSeconds() < 1
                || properties.retryBaseMs() < 1
                || properties.pollMs() < 50)
            throw new IllegalArgumentException("Invalid worker configuration");
    }

    @Scheduled(fixedDelay = 300)
    public void tick() {
        if (!properties.workerEnabled() || closing) return;
        for (Task task : tasks.due(System.currentTimeMillis())) {
            if (active.size() >= properties.concurrency()) break;
            if (active.add(task.id()))
                pool.submit(
                        () -> {
                            try {
                                run(task.id());
                            } finally {
                                active.remove(task.id());
                            }
                        });
        }
    }

    private static final class Execution {
        private final String taskId;
        private String promptId;

        private Execution(String taskId) {
            this.taskId = taskId;
        }
    }

    public void run(String taskId) {
        Execution execution = new Execution(taskId);
        WebSocket socket = null;
        try {
            Task task = tasks.require(taskId);
            if (task.state().terminal()) return;
            execution.promptId = task.promptId();
            socket = connectProgress(taskId);
            if (execution.promptId != null) {
                if (reconcile(task)) return;
            } else if (!submit(execution)) return;
            pollUntilDone(execution);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            handleFailure(execution, exception);
        } finally {
            if (socket != null) socket.abort();
        }
    }

    private WebSocket connectProgress(String taskId) {
        try {
            return comfy.connect(taskId, message -> onMessage(taskId, message));
        } catch (Exception exception) {
            LOG.warn("Progress connection failed for {}; using history polling", taskId, exception);
            return null;
        }
    }

    private boolean reconcile(Task task) throws Exception {
        PromptHistory history = recovery.reconcile(task);
        if (history.exists()) {
            completion.complete(task.id(), task.promptId(), history);
            return true;
        }
        return false;
    }

    private boolean submit(Execution execution) throws Exception {
        synchronized (lifecycle) {
            Task task = tasks.require(execution.taskId);
            if (task.state().terminal()) return false;
            if (task.cancelRequested()) {
                lifecycle.transition(task.id(), TaskState.CANCELLED, "Cancelled before submit");
                return false;
            }
            execution.promptId = UUID.randomUUID().toString();
            lifecycle.beginAttempt(task, execution.promptId);
            // 锁覆盖 HTTP 提交，防止本地取消先于远端接收；该全局锁仅保护单 JVM。
            String returned =
                    comfy.submit(Json.read(task.workflow()), execution.promptId, task.id());
            if (!execution.promptId.equals(returned))
                throw new ComfyClient.RemoteError("ComfyUI did not preserve prompt_id");
            return true;
        }
    }

    private void pollUntilDone(Execution execution) throws Exception {
        while (!closing) {
            Task task = tasks.require(execution.taskId);
            if (task.state().terminal()) return;
            PromptHistory history = comfy.history(execution.promptId);
            if (history.exists()) {
                completion.complete(task.id(), execution.promptId, history);
                return;
            }
            if (confirmCancellation(task, execution.promptId)) return;
            enforceTimeout(task, execution.promptId);
            Thread.sleep(properties.pollMs());
        }
    }

    private boolean confirmCancellation(Task task, String promptId) throws Exception {
        if (!task.cancelRequested()) return false;
        comfy.cancel(promptId);
        if (comfy.queue().contains(promptId)) return false;
        lifecycle.transition(task.id(), TaskState.CANCELLED, "Cancellation confirmed by queue");
        return true;
    }

    private void enforceTimeout(Task task, String promptId) throws Exception {
        if (System.currentTimeMillis() - task.startedAt() > properties.timeoutSeconds() * 1000) {
            comfy.cancel(promptId);
            throw new ComfyClient.RemoteError("Task attempt timeout");
        }
    }

    private void handleFailure(Execution execution, Exception exception) {
        if (closing) return;
        if (!recovery.deferIfUncertain(execution.taskId, execution.promptId, exception))
            failures.handleFailure(execution.taskId, exception);
    }

    public void onMessage(String taskId, ComfyMessage message) {
        String expected = tasks.require(taskId).promptId();
        if (expected == null || !expected.equals(message.promptId())) return;
        switch (message.type()) {
            case "progress" -> {
                if (message.max() > 0)
                    lifecycle.running(taskId, (int) (100L * message.value() / message.max()));
            }
            case "executing" -> {
                if (!message.nodeIsNull()) lifecycle.running(taskId, 0);
            }
            case "executed" -> lifecycle.running(taskId, 99);
            default -> {}
        }
    }

    @PreDestroy
    public void stop() {
        closing = true;
        pool.shutdownNow();
    }
}
