package dev.comfybatch.repository;

import dev.comfybatch.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TemplateRepository {
    private static final RowMapper<Template> ROW_MAPPER =
            (row, rowNumber) ->
                    new Template(
                            row.getString("id"),
                            row.getString("name"),
                            row.getString("workflow"),
                            row.getString("mappings"),
                            row.getLong("created_at"));
    private final JdbcTemplate jdbc;

    public TemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Template require(String id) {
        return jdbc.query("select * from cb_template where id=?", ROW_MAPPER, id).stream()
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }

    public List<Template> findAll() {
        return jdbc.query("select * from cb_template order by created_at desc", ROW_MAPPER);
    }

    public void insert(Template template) {
        jdbc.update(
                "insert into cb_template values(?,?,?,?,?)",
                template.id(),
                template.name(),
                template.workflow(),
                template.mappings(),
                template.createdAt());
    }
}
