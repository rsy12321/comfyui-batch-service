package dev.comfybatch.repository;

import dev.comfybatch.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ImageResultRepository {
    private static final RowMapper<ImageResult> ROW_MAPPER =
            (row, rowNumber) ->
                    new ImageResult(
                            row.getString("id"),
                            row.getString("task_id"),
                            row.getString("path"),
                            row.getString("sha256"),
                            row.getInt("width"),
                            row.getInt("height"),
                            row.getLong("seed"));
    private final JdbcTemplate jdbc;

    public ImageResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ImageResult> findByTask(String taskId) {
        return jdbc.query("select * from cb_result where task_id=?", ROW_MAPPER, taskId);
    }

    public ImageResult require(String id) {
        return jdbc.query("select * from cb_result where id=?", ROW_MAPPER, id).stream()
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    public boolean existsPath(String path) {
        return jdbc.queryForObject(
                        "select count(*) from cb_result where path=?", Integer.class, path)
                != 0;
    }

    public void insert(String taskId, StoredImage image, long seed) {
        jdbc.update(
                "insert into cb_result values(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                taskId,
                image.path(),
                image.sha256(),
                image.width(),
                image.height(),
                seed);
    }
}
