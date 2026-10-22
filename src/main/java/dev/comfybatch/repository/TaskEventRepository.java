package dev.comfybatch.repository;

import dev.comfybatch.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TaskEventRepository {
    private static final RowMapper<TaskEvent> ROW_MAPPER =
            (row, rowNumber) ->
                    new TaskEvent(
                            row.getString("id"),
                            row.getString("task_id"),
                            row.getString("from_state"),
                            row.getString("to_state"),
                            row.getString("reason"),
                            row.getLong("happened_at"));
    private final JdbcTemplate jdbc;

    public TaskEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TaskEvent> findByTask(String taskId) {
        return jdbc.query(
                "select * from cb_event where task_id=? order by happened_at,id",
                ROW_MAPPER,
                taskId);
    }

    public void insert(String taskId, String from, String to, String reason, long now) {
        jdbc.update(
                "insert into cb_event(id,task_id,from_state,to_state,reason,happened_at) values(?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                taskId,
                from,
                to,
                reason,
                now);
    }
}
