package dev.comfybatch.service;

import dev.comfybatch.domain.*;
import dev.comfybatch.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskLifecycle {
    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final TaskAttemptRepository attempts;
    private final TransactionTemplate transactions;

    public TaskLifecycle(
            TaskRepository tasks,
            TaskEventRepository events,
            TaskAttemptRepository attempts,
            TransactionTemplate transactions) {
        this.tasks = tasks;
        this.events = events;
        this.attempts = attempts;
        this.transactions = transactions;
    }

    // 沿用 v1 的 JVM 全局监视器，串行化提交、取消和状态变更；不提供多实例互斥。
    public synchronized void transition(String taskId, TaskState next, String reason) {
        transactions.executeWithoutResult(
                transaction -> {
                    TaskState previous = tasks.require(taskId).state();
                    if (!previous.permits(next))
                        throw new IllegalStateException(
                                "Illegal transition " + previous + " -> " + next);
                    tasks.setState(taskId, next, System.currentTimeMillis());
                    event(taskId, previous.name(), next.name(), reason);
                });
    }

    public void event(String taskId, String from, String to, String reason) {
        events.insert(taskId, from, to, reason, System.currentTimeMillis());
    }

    public synchronized void running(String taskId, int progress) {
        Task task = tasks.require(taskId);
        if (task.state().terminal()) return;
        if (task.state() == TaskState.SUBMITTED)
            transition(taskId, TaskState.RUNNING, "ComfyUI execution event");
        tasks.setProgress(taskId, Math.max(0, Math.min(99, progress)), System.currentTimeMillis());
    }

    public void beginAttempt(Task task, String promptId) {
        // 先持久化 prompt_id，再调用远端：崩溃后可按相同 ID 查询历史与队列，避免盲目重提。
        int attempt = task.attempt() + 1;
        long now = System.currentTimeMillis();
        transactions.executeWithoutResult(
                transaction -> {
                    tasks.beginAttempt(task.id(), promptId, attempt, now);
                    attempts.insert(task.id(), attempt, promptId, now);
                    if (tasks.require(task.id()).state() == TaskState.PENDING)
                        transition(
                                task.id(),
                                TaskState.SUBMITTED,
                                "Submission intent persisted before HTTP");
                });
    }

    public synchronized void cancel(String taskId) {
        Task task = tasks.require(taskId);
        if (task.state().terminal()) return;
        tasks.requestCancel(taskId, System.currentTimeMillis());
        if (task.promptId() == null)
            transition(taskId, TaskState.CANCELLED, "Cancelled local pending task");
        else event(taskId, task.state().name(), task.state().name(), "Cancellation requested");
    }
}
