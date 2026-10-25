package com.skala.chagok.domain.agent.repository;

import com.skala.chagok.global.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 저장된 본부 성과는 같은 공용 DB에서 읽는다. 조회에는 실행 중인 에이전트가 필요하지 않다. */
@Repository @RequiredArgsConstructor @Transactional(readOnly = true)
public class HqReportRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final NamedParameterJdbcTemplate jdbc;

    public Map<String, Object> list() {
        if (!available()) return Map.of("items", List.of());
        var rows = jdbc.query("select month, updated_at, summary is not null as has_summary "
                + "from box_agent.hq_report order by month desc", Map.of(), (rs, i) -> Map.of(
                "month", rs.getString("month"), "generatedAt", rs.getTimestamp("updated_at").toInstant().toString(),
                "hasSummary", rs.getBoolean("has_summary")));
        return Map.of("items", rows);
    }

    public Map<String, Object> get(String month) {
        if (!available()) throw BusinessException.notFound("본부 리포트");
        var rows = jdbc.query("select month, html, data, summary, recommendations, updated_at "
                + "from box_agent.hq_report where month = :month", Map.of("month", month), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", rs.getString("month"));
            row.put("html", rs.getString("html"));
            for (String key : List.of("data", "summary", "recommendations")) row.put(key, json(rs, key));
            row.put("generatedAt", rs.getTimestamp("updated_at").toInstant().toString());
            return row;
        });
        if (rows.isEmpty()) throw BusinessException.notFound("본부 리포트");
        return rows.getFirst();
    }

    private boolean available() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('box_agent.hq_report') is not null",
                Map.of(), Boolean.class));
    }

    private static Object json(ResultSet rs, String key) throws SQLException {
        String value = rs.getString(key);
        return value == null ? null : JSON.readValue(value, Object.class);
    }
}
