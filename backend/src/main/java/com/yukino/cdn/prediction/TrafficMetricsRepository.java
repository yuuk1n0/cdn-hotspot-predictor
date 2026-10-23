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

    Optional<LocalDate> latestObservedDate(String datasetId, String appId) {
        var values = jdbc.query("SELECT MAX(o.observed_date) FROM file_daily_observation o "
                        + "JOIN imported_dataset d ON d.dataset_id=o.dataset_id "
                        + "WHERE d.dataset_id=:dataset AND d.app_id=:app AND d.status='READY' AND d.dataset_source='REAL'",
                new MapSqlParameterSource("dataset", datasetId).addValue("app", appId),
                (rs, index) -> rs.getDate(1));
        return values.isEmpty() || values.get(0) == null ? Optional.empty() : Optional.of(values.get(0).toLocalDate());
    }

    TotalWindow total(String appId, LocalDate start, LocalDate end) {
        // A prediction snapshot references a FILE_DAILY dataset. App totals are a
        // separate immutable dataset version, so they must be resolved by app/type
        // rather than by joining against the snapshot's dataset id.
        var datasets = jdbc.query("SELECT dataset_id FROM imported_dataset "
                        + "WHERE app_id=:app AND dataset_type='APP_DAILY_TOTAL' AND dataset_source='REAL' AND status='READY' "
                        + "ORDER BY created_at_ms DESC,dataset_id DESC LIMIT 1",
                new MapSqlParameterSource("app", appId), (rs, index) -> rs.getString(1));
        if (datasets.isEmpty()) return new TotalWindow(null, 0, null, null);
        String datasetId = datasets.get(0);
        var sql = "SELECT COUNT(DISTINCT observed_date),SUM(total_traffic_bytes),MIN(source_unit),MAX(source_unit) "
                + "FROM app_daily_total WHERE dataset_id=:dataset AND app_id=:app "
                + "AND observed_date BETWEEN :start AND :end";
        return jdbc.queryForObject(sql, new MapSqlParameterSource("dataset", datasetId).addValue("app", appId)
                        .addValue("start", start).addValue("end", end),
                (rs, index) -> {
                    String minUnit = rs.getString(3);
                    String maxUnit = rs.getString(4);
                    String unit = minUnit == null ? null : minUnit.equals(maxUnit) ? minUnit : "MIXED";
                    return new TotalWindow(datasetId, rs.getInt(1), rs.getBigDecimal(2), unit);
                });
    }

    Map<String, BigDecimal> actualByHashes(String datasetId, String appId, LocalDate start, LocalDate end,
                                           Collection<String> hashes) {
        if (hashes.isEmpty()) return Map.of();
        var sql = "SELECT o.file_key_hash,SUM(o.traffic_bytes) FROM file_daily_observation o "
                + "JOIN imported_dataset d ON d.dataset_id=o.dataset_id "
                + "WHERE d.dataset_id=:dataset AND d.app_id=:app AND d.status='READY' AND d.dataset_source='REAL' "
                + "AND o.observed_date BETWEEN :start AND :end AND o.file_key_hash IN (:hashes) GROUP BY o.file_key_hash";
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("dataset", datasetId).addValue("app", appId)
                        .addValue("start", start).addValue("end", end).addValue("hashes", hashes),
                rs -> { result.put(rs.getString(1), rs.getBigDecimal(2)); });
        return result;
    }

    List<ActualFile> topActual(String datasetId, String appId, LocalDate start, LocalDate end, int limit) {
        var sql = "SELECT o.file_key,SUM(o.traffic_bytes) AS total_bytes FROM file_daily_observation o "
                + "JOIN imported_dataset d ON d.dataset_id=o.dataset_id "
                + "WHERE d.dataset_id=:dataset AND d.app_id=:app AND d.status='READY' AND d.dataset_source='REAL' "
                + "AND o.observed_date BETWEEN :start AND :end "
                + "GROUP BY o.file_key_hash,o.file_key ORDER BY total_bytes DESC,o.file_key ASC LIMIT :limit";
        return jdbc.query(sql, new MapSqlParameterSource("dataset", datasetId).addValue("app", appId)
                        .addValue("start", start).addValue("end", end).addValue("limit", limit),
                (rs, index) -> new ActualFile(rs.getString(1), rs.getBigDecimal(2)));
    }

    record TotalWindow(String datasetId, int observedDays, BigDecimal totalBytes, String sourceUnit) {}
    record ActualFile(String fileKey, BigDecimal actualBytes) {}
}
