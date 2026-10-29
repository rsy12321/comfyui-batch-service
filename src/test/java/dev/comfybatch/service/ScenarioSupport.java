package dev.comfybatch.service;

import dev.comfybatch.api.ApiModels.*;
import dev.comfybatch.comfy.*;
import dev.comfybatch.config.AppProperties;
import dev.comfybatch.domain.*;
import dev.comfybatch.repository.*;
import dev.comfybatch.support.Json;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
public abstract class ScenarioSupport {
    @Autowired protected TestDataRepository data;
    @Autowired protected TaskRepository tasks;
    @Autowired protected TaskEventRepository events;
    @Autowired protected TaskAttemptRepository attempts;
    @Autowired protected ImageResultRepository results;
    @Autowired protected JobService jobs;
    @Autowired protected TaskLifecycle lifecycle;
    @Autowired protected TaskFailureHandler failures;
    @Autowired protected TaskWorker worker;
    @Autowired protected TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        data.clear();
    }

    public static TemplateRequest template() {
        var workflow =
                Json.read(
                        """
   {"1":{"class_type":"TestNode","inputs":{"prompt":"p","negative_prompt":"n",
   "seed":1,"width":512,"height":512,"steps":20}}}
   """);
        var mappings = new HashMap<String, ParameterMapping>();
        for (String name : List.of("prompt", "negative_prompt", "seed", "width", "height", "steps"))
            mappings.put(name, new ParameterMapping("1", name));
        return new TemplateRequest("test", workflow, mappings);
    }

    protected String task() {
        String templateId = jobs.template(template()).id();
        String jobId =
                jobs.submit(new SubmitRequest(templateId, Map.of(), 1, List.of(42L)), null).id();
        return tasks.findByJob(jobId).getFirst().id();
    }

    protected String persistedTask(String promptId, long startedAt) {
        String taskId = task();
        lifecycle.transition(taskId, TaskState.SUBMITTED, "before restart");
        data.persistedAttempt(taskId, promptId, startedAt);
        return taskId;
    }

    protected AppProperties properties(String url, long timeout) {
        return new AppProperties(
                "test-key-not-secret", url, 1, timeout, 1, 50, "target/test-storage", 4, false);
    }

    protected TaskWorker workerFor(String url, long timeout) {
        AppProperties properties = properties(url, timeout);
        var client = new ComfyClient(properties);
        var failureHandler =
                new TaskFailureHandler(tasks, attempts, lifecycle, transactions, properties);
        var recovery = new TaskRecovery(client, lifecycle, failureHandler);
        var storage = new ResultStorage(properties, client);
        var completion =
                new TaskCompletion(tasks, attempts, results, lifecycle, storage, transactions);
        return new TaskWorker(
                tasks, client, properties, lifecycle, failureHandler, recovery, completion);
    }
}
