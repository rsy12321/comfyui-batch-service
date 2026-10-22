package dev.comfybatch.repository;

import dev.comfybatch.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TaskAttemptRepository {
    private static final RowMapper<TaskAttempt> ROW_MAPPER =
            (row, rowNumber) ->
                    new TaskAttempt(
                            row.getString("id"),
                            row.getString("task_id"),
                            row.getInt("attempt_no"),
                            row.getString("prompt_id"),
                            row.getString("error"),
                            row.getLong("started_at"),
                            row.getObject("ended_at", Long.class));
    private final JdbcTemplate jdbc;

    public TaskAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TaskAttempt> findByTask(String taskId) {
        return jdbc.query(
                "select * from cb_attempt where task_id=? order by attempt_no", ROW_MAPPER, taskId);
    }

    public void insert(String taskId, int attempt, String promptId, long now) {
        jdbc.update(
                "insert into cb_attempt(id,task_id,attempt_no,prompt_id,started_at) values(?,?,?,?,?)",
                UUID.randomUUID().toString(),
                taskId,
                attempt,
                promptId,
                now);
    }

    public void finish(String promptId, long now) {
        jdbc.update("update cb_attempt set ended_at=? where prompt_id=?", now, promptId);
    }

    public void fail(String taskId, int attempt, String error, long now) {
        jdbc.update(
                "update cb_attempt set error=?,ended_at=? where task_id=? and attempt_no=?",
                error,
                now,
                taskId,
                attempt);
    }
}
