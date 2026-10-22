package dev.comfybatch.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TestDataRepository {
    private final JdbcTemplate jdbc;

    public TestDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        for (String table :
                List.of("cb_result", "cb_attempt", "cb_event", "cb_task", "cb_job", "cb_template"))
            jdbc.update("delete from " + table);
    }

    public int taskCount() {
        return jdbc.queryForObject("select count(*) from cb_task", Integer.class);
    }

    public int jobCount() {
        return jdbc.queryForObject("select count(*) from cb_job", Integer.class);
    }

    public void setAttempt(String taskId, int attempt) {
        jdbc.update("update cb_task set attempt=? where id=?", attempt, taskId);
    }

    public void setPrompt(String taskId, String promptId) {
        jdbc.update("update cb_task set prompt_id=? where id=?", promptId, taskId);
    }

    public void persistedAttempt(String taskId, String promptId, long startedAt) {
        jdbc.update(
                "update cb_task set prompt_id=?,attempt=1,started_at=? where id=?",
                promptId,
                startedAt,
                taskId);
    }
}
