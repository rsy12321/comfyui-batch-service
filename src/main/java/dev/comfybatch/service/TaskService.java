package dev.comfybatch.service;

import dev.comfybatch.api.ApiModels.*;
import dev.comfybatch.domain.Task;
import dev.comfybatch.repository.*;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
    private final TaskRepository tasks;
    private final JobRepository jobs;
    private final TaskEventRepository events;
    private final TaskAttemptRepository attempts;
    private final ImageResultRepository results;
    private final TaskLifecycle lifecycle;
    private final ResultStorage storage;

    public TaskService(
            TaskRepository tasks,
            JobRepository jobs,
            TaskEventRepository events,
            TaskAttemptRepository attempts,
            ImageResultRepository results,
            TaskLifecycle lifecycle,
            ResultStorage storage) {
        this.tasks = tasks;
        this.jobs = jobs;
        this.events = events;
        this.attempts = attempts;
        this.results = results;
        this.lifecycle = lifecycle;
        this.storage = storage;
    }

    public TaskResponse detail(String id) {
        return new TaskResponse(
                TaskFields.from(tasks.require(id)),
                events.findByTask(id).stream().map(EventResponse::from).toList(),
                attempts.findByTask(id).stream().map(AttemptResponse::from).toList(),
                results.findByTask(id).stream().map(ResultResponse::from).toList());
    }

    public TaskResponse cancel(String id) {
        lifecycle.cancel(id);
        return detail(id);
    }

    public void cancelJob(String id) {
        jobs.require(id);
        for (Task task : tasks.findByJob(id)) lifecycle.cancel(task.id());
    }

    public Path resultFile(String id) {
        return storage.requireFile(results.require(id).path());
    }
}
