package com.yukino.cdn.prediction;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TrafficMetricsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public TrafficMetricsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LocalDate> latestObservedDate(String appId, String datasetSource) {
        var values = jdbc.query("SELECT MAX(observed_date) FROM file_daily_observation "
                        + "WHERE app_id=:app AND dataset_source=:source",
                new MapSqlParameterSource("app", appId).addValue("source", datasetSource),
                (rs, index) -> rs.getDate(1));
        return values.isEmpty() || values.get(0) == null ? Optional.empty() : Optional.of(values.get(0).toLocalDate());
    }

    TotalWindow total(String appId, LocalDate start, LocalDate end, String datasetSource) {
        var sql = "SELECT COUNT(DISTINCT observed_date),SUM(total_traffic_bytes) FROM app_daily_total "
                + "WHERE app_id=:app AND observed_date BETWEEN :start AND :end AND dataset_source=:source";
        return jdbc.queryForObject(sql, new MapSqlParameterSource("app", appId).addValue("start", start)
                        .addValue("end", end).addValue("source", datasetSource),
                (rs, index) -> new TotalWindow(rs.getInt(1), rs.getBigDecimal(2)));
    }

    Map<String, BigDecimal> actualByHashes(String appId, LocalDate start, LocalDate end,
                                           String datasetSource, Collection<String> hashes) {
        if (hashes.isEmpty()) return Map.of();
        var sql = "SELECT file_key_hash,SUM(traffic_bytes) FROM file_daily_observation "
                + "WHERE app_id=:app AND observed_date BETWEEN :start AND :end "
                + "AND dataset_source=:source AND file_key_hash IN (:hashes) GROUP BY file_key_hash";
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("app", appId).addValue("start", start).addValue("end", end)
                        .addValue("source", datasetSource).addValue("hashes", hashes),
                rs -> { result.put(rs.getString(1), rs.getBigDecimal(2)); });
        return result;
    }

    List<ActualFile> topActual(String appId, LocalDate start, LocalDate end, String datasetSource, int limit) {
        var sql = "SELECT file_key,SUM(traffic_bytes) AS total_bytes FROM file_daily_observation "
                + "WHERE app_id=:app AND observed_date BETWEEN :start AND :end AND dataset_source=:source "
                + "GROUP BY file_key_hash,file_key ORDER BY total_bytes DESC,file_key ASC LIMIT :limit";
        return jdbc.query(sql, new MapSqlParameterSource("app", appId).addValue("start", start).addValue("end", end)
                        .addValue("source", datasetSource).addValue("limit", limit),
                (rs, index) -> new ActualFile(rs.getString(1), rs.getBigDecimal(2)));
    }

    record TotalWindow(int observedDays, BigDecimal totalBytes) {}
    record ActualFile(String fileKey, BigDecimal actualBytes) {}
}
