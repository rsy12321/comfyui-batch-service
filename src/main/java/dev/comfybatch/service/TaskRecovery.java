package dev.comfybatch.service;

import dev.comfybatch.comfy.*;
import dev.comfybatch.domain.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(TaskRecovery.class);
    private final ComfyClient comfy;
    private final TaskLifecycle lifecycle;
    private final TaskFailureHandler failures;

    public TaskRecovery(ComfyClient comfy, TaskLifecycle lifecycle, TaskFailureHandler failures) {
        this.comfy = comfy;
        this.lifecycle = lifecycle;
        this.failures = failures;
    }

    public PromptHistory reconcile(Task task) throws Exception {
        PromptHistory history = comfy.history(task.promptId());
        if (history.exists()) return history;
        // 历史存在则直接恢复结果；仍在队列则继续等待；二者均无才进入原有重试流程。
        // 历史丢失与提交前崩溃无法区分，因此仍不保证 exactly-once。
        if (!comfy.queue().contains(task.promptId()))
            throw new ComfyClient.RemoteError(
                    "Recovery: prompt absent from history and queue; retry with recorded new attempt");
        lifecycle.event(
                task.id(),
                task.state().name(),
                task.state().name(),
                "Recovered existing ComfyUI prompt " + task.promptId());
        return history;
    }

    public boolean deferIfUncertain(String taskId, String promptId, Exception error) {
        if (promptId == null
                || (error instanceof ComfyClient.RemoteError
                        && error.getMessage().startsWith("HTTP 4"))) return false;
        try {
            if (comfy.queue().contains(promptId)) {
                failures.defer(
                        taskId, "ComfyUI prompt still queued/running; continue reconciliation");
                return true;
            }
        } catch (Exception exception) {
            LOG.warn("Cannot reconcile prompt {}; preserving its identity", promptId, exception);
            failures.defer(taskId, "ComfyUI unavailable; preserve prompt_id for recovery");
            return true;
        }
        return false;
    }
}
