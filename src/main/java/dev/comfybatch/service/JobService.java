package dev.comfybatch.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.comfybatch.api.ApiModels.*;
import dev.comfybatch.domain.*;
import dev.comfybatch.repository.*;
import dev.comfybatch.support.Json;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {
    private final TemplateRepository templates;
    private final JobRepository jobs;
    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final TransactionTemplate transactions;

    public JobService(
            TemplateRepository templates,
            JobRepository jobs,
            TaskRepository tasks,
            TaskEventRepository events,
            TransactionTemplate transactions) {
        this.templates = templates;
        this.jobs = jobs;
        this.tasks = tasks;
        this.events = events;
        this.transactions = transactions;
    }

    public CreatedResponse template(TemplateRequest request) {
        WorkflowParameters.validateTemplate(request);
        String id = UUID.randomUUID().toString();
        templates.insert(
                new Template(
                        id,
                        request.name(),
                        Json.write(request.workflow()),
                        Json.write(request.mappings()),
                        System.currentTimeMillis()));
        return new CreatedResponse(id);
    }

    public List<TemplateResponse> templates() {
        return templates.findAll().stream().map(TemplateResponse::from).toList();
    }

    public synchronized SubmissionResponse submit(SubmitRequest request, String key) {
        WorkflowParameters.validateRequest(request, key);
        String hash = Json.hashRequest(request);
        return transactions.execute(
                transaction -> {
                    if (key != null) {
                        var existing = jobs.findByKey(key);
                        if (existing.isPresent()) return replay(existing.get(), hash);
                    }
                    return createBatch(request, key, hash);
                });
    }

    private SubmissionResponse replay(Job existing, String hash) {
        if (!hash.equals(existing.requestHash()))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Key reused for a different request");
        return new SubmissionResponse(existing.id(), true);
    }

    private SubmissionResponse createBatch(SubmitRequest request, String key, String hash) {
        Template template = templates.require(request.templateId());
        JsonNode mappings = Json.read(template.mappings());
        JsonNode workflow =
                WorkflowParameters.apply(
                        Json.read(template.workflow()), mappings, request.parameters());
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jobs.insert(new Job(id, request.templateId(), key, hash, now));
        for (int i = 0; i < request.count(); i++) {
            long seed = seedFor(request, i);
            String taskId = UUID.randomUUID().toString();
            tasks.insert(
                    taskId,
                    id,
                    i,
                    Json.write(WorkflowParameters.withSeed(workflow, mappings, seed)),
                    seed,
                    now);
            events.insert(taskId, null, "PENDING", "Batch created", System.currentTimeMillis());
        }
        return new SubmissionResponse(id, false);
    }

    private long seedFor(SubmitRequest request, int index) {
        if (request.seeds() != null) return request.seeds().get(index);
        return request.parameters().containsKey("seed")
                ? Math.addExact(request.parameters().get("seed").longValue(), index)
                : ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    }

    public JobResponse detail(String id) {
        Job job = jobs.require(id);
        List<Task> children = tasks.findByJob(id);
        return new JobResponse(
                job.id(),
                job.templateId(),
                job.createdAt(),
                children.stream().map(TaskSummary::from).toList(),
                aggregate(children));
    }

    public PageResponse list(int page, int size, TaskState state) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw WorkflowParameters.invalid("Invalid pagination");
        long total = jobs.count(state);
        List<JobResponse> items =
                jobs.page(page, size, state).stream().map(job -> detail(job.id())).toList();
        return new PageResponse(page, size, total, items);
    }

    public static TaskState aggregate(List<Task> tasks) {
        List<TaskState> states = tasks.stream().map(Task::state).toList();
        if (states.stream().allMatch(TaskState.SUCCEEDED::equals)) return TaskState.SUCCEEDED;
        if (states.stream()
                .anyMatch(state -> state == TaskState.RUNNING || state == TaskState.SUBMITTED))
            return TaskState.RUNNING;
        if (states.contains(TaskState.PENDING)) return TaskState.PENDING;
        return states.contains(TaskState.FAILED) ? TaskState.FAILED : TaskState.CANCELLED;
    }
}
