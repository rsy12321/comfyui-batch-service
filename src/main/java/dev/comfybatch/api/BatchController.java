package dev.comfybatch.api;

import dev.comfybatch.api.ApiModels.*;
import dev.comfybatch.domain.TaskState;
import dev.comfybatch.service.JobService;
import dev.comfybatch.service.TaskService;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BatchController {
    private final JobService jobs;
    private final TaskService tasks;

    public BatchController(JobService jobs, TaskService tasks) {
        this.jobs = jobs;
        this.tasks = tasks;
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse template(@RequestBody TemplateRequest request) {
        return jobs.template(request);
    }

    @GetMapping("/templates")
    public List<TemplateResponse> templates() {
        return jobs.templates();
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionResponse submit(
            @RequestBody SubmitRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return jobs.submit(request, key);
    }

    @GetMapping("/jobs/{id}")
    public JobResponse detail(@PathVariable String id) {
        return jobs.detail(id);
    }

    @GetMapping("/jobs")
    public PageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TaskState state) {
        return jobs.list(page, size, state);
    }

    @GetMapping("/tasks/{id}")
    public TaskResponse task(@PathVariable String id) {
        return tasks.detail(id);
    }

    @PostMapping("/tasks/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskResponse cancel(@PathVariable String id) {
        return tasks.cancel(id);
    }

    @PostMapping("/jobs/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse cancelJob(@PathVariable String id) {
        tasks.cancelJob(id);
        return jobs.detail(id);
    }

    @GetMapping("/results/{id}/file")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("Content-Disposition", "attachment; filename=\"" + id + ".png\"")
                .body(new FileSystemResource(tasks.resultFile(id)));
    }
}
