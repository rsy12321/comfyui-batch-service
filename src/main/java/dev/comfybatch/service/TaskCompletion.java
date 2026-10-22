package dev.comfybatch.service;

import dev.comfybatch.comfy.*;
import dev.comfybatch.domain.*;
import dev.comfybatch.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskCompletion {
    private final TaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final ImageResultRepository results;
    private final TaskLifecycle lifecycle;
    private final ResultStorage storage;
    private final TransactionTemplate transactions;

    public TaskCompletion(
            TaskRepository tasks,
            TaskAttemptRepository attempts,
            ImageResultRepository results,
            TaskLifecycle lifecycle,
            ResultStorage storage,
            TransactionTemplate transactions) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.results = results;
        this.lifecycle = lifecycle;
        this.storage = storage;
        this.transactions = transactions;
    }

    public void complete(String taskId, String promptId, PromptHistory history) throws Exception {
        Task task = tasks.require(taskId);
        if (task.state().terminal()) return;
        if (!history.succeeded()) {
            if (task.cancelRequested()) {
                lifecycle.transition(
                        taskId,
                        TaskState.CANCELLED,
                        "ComfyUI interrupted; " + history.statusDescription());
                return;
            }
            throw new ComfyClient.RemoteError("Execution failed: " + history.statusDescription());
        }
        List<StoredImage> images = new ArrayList<>();
        int index = 0;
        for (OutputImage output : history.images())
            images.add(storage.download(taskId, promptId, index++, output));
        if (images.isEmpty())
            throw new ComfyClient.RemoteError("No output images in successful history");
        persist(task, promptId, images);
    }

    private void persist(Task task, String promptId, List<StoredImage> images) {
        synchronized (lifecycle) {
            if (tasks.require(task.id()).state().terminal()) return;
            transactions.executeWithoutResult(
                    transaction -> {
                        for (StoredImage image : images)
                            if (!results.existsPath(image.path()))
                                results.insert(task.id(), image, task.seed());
                        tasks.markDownloaded(task.id());
                        attempts.finish(promptId, System.currentTimeMillis());
                        lifecycle.transition(
                                task.id(),
                                TaskState.SUCCEEDED,
                                "History success; PNG downloaded and hashed");
                    });
        }
    }
}
