package dev.comfybatch.repository;

import dev.comfybatch.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {
    private static final RowMapper<Task> ROW_MAPPER =
            (row, rowNumber) ->
                    new Task(
                            row.getString("id"),
                            row.getString("job_id"),
                            row.getInt("ordinal_no"),
                            TaskState.valueOf(row.getString("state")),
                            row.getString("workflow"),
                            row.getLong("seed"),
                            row.getString("prompt_id"),
                            row.getInt("attempt"),
                            row.getInt("progress"),
                            row.getString("error"),
                            row.getLong("created_at"),
                            row.getLong("updated_at"),
                            row.getObject("started_at", Long.class),
                            row.getLong("next_run_at"),
                            row.getBoolean("cancel_requested"));
    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Task require(String id) {
        return jdbc.query("select * from cb_task where id=?", ROW_MAPPER, id).stream()
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    public List<Task> findByJob(String jobId) {
        return jdbc.query(
                "select * from cb_task where job_id=? order by ordinal_no", ROW_MAPPER, jobId);
    }

    public List<Task> due(long now) {
        return jdbc.query(
                "select * from cb_task where state in ('PENDING','SUBMITTED','RUNNING')"
                        + " and next_run_at<=? order by created_at,ordinal_no",
                ROW_MAPPER,
                now);
    }

    public void insert(String id, String jobId, int ordinal, String workflow, long seed, long now) {
        jdbc.update(
                "insert into cb_task(id,job_id,ordinal_no,state,workflow,seed,created_at,"
                        + "updated_at,next_run_at) values(?,?,?,?,?,?,?,?,?)",
                id,
                jobId,
                ordinal,
                "PENDING",
                workflow,
                seed,
                now,
                now,
                now);
    }

    public void setState(String id, TaskState state, long now) {
        jdbc.update("update cb_task set state=?,updated_at=? where id=?", state.name(), now, id);
    }

    public void setProgress(String id, int progress, long now) {
        jdbc.update("update cb_task set progress=?,updated_at=? where id=?", progress, now, id);
    }

    public void beginAttempt(String id, String promptId, int attempt, long now) {
        jdbc.update(
                "update cb_task set prompt_id=?,attempt=?,started_at=?,progress=0 where id=?",
                promptId,
                attempt,
                now,
                id);
    }

    public void markDownloaded(String id) {
        jdbc.update("update cb_task set progress=100,error=null where id=?", id);
    }

    public void defer(String id, long nextRun, String reason) {
        jdbc.update("update cb_task set next_run_at=?,error=? where id=?", nextRun, reason, id);
    }

    public void setError(String id, String error, long now) {
        jdbc.update("update cb_task set error=?,updated_at=? where id=?", error, now, id);
    }

    public void setNextRun(String id, long nextRun) {
        jdbc.update("update cb_task set next_run_at=? where id=?", nextRun, id);
    }

    public void scheduleRetry(String id, long nextRun) {
        jdbc.update("update cb_task set prompt_id=null,next_run_at=? where id=?", nextRun, id);
    }

    public void requestCancel(String id, long now) {
        jdbc.update("update cb_task set cancel_requested=true,next_run_at=? where id=?", now, id);
    }
}
