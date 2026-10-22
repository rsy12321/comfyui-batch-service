package dev.comfybatch.repository;

import dev.comfybatch.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {
    private static final RowMapper<Job> ROW_MAPPER =
            (row, rowNumber) ->
                    new Job(
                            row.getString("id"),
                            row.getString("template_id"),
                            row.getString("idempotency_key"),
                            row.getString("request_hash"),
                            row.getLong("created_at"));
    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Job require(String id) {
        return jdbc.query("select * from cb_job where id=?", ROW_MAPPER, id).stream()
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    public Optional<Job> findByKey(String key) {
        return jdbc.query("select * from cb_job where idempotency_key=?", ROW_MAPPER, key).stream()
                .findFirst();
    }

    public void insert(Job job) {
        jdbc.update(
                "insert into cb_job values(?,?,?,?,?)",
                job.id(),
                job.templateId(),
                job.idempotencyKey(),
                job.requestHash(),
                job.createdAt());
    }

    private String filter(TaskState state) {
        return state == null
                ? ""
                : " where exists(select 1 from cb_task t where t.job_id=j.id and t.state=?)";
    }

    public long count(TaskState state) {
        return jdbc.queryForObject(
                "select count(*) from cb_job j" + filter(state),
                Long.class,
                state == null ? new Object[] {} : new Object[] {state.name()});
    }

    public List<Job> page(int page, int size, TaskState state) {
        return jdbc.query(
                "select j.* from cb_job j"
                        + filter(state)
                        + " order by j.created_at desc,j.id limit ? offset ?",
                ROW_MAPPER,
                state == null
                        ? new Object[] {size, (long) page * size}
                        : new Object[] {state.name(), size, (long) page * size});
    }
}
