package com.yukino.cdn.prediction;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PredictionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PredictionRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    void insert(PredictionTask task) {
        jdbc.update("""
                INSERT INTO prediction_task (task_id, idempotency_key_hash, request_hash, dataset_id, app_id, cutoff_date,
                horizon_days, model_version, status, stage, created_at_ms, updated_at_ms, deadline_at_ms, next_attempt_at_ms)
                VALUES (:id, :keyHash, :requestHash, :datasetId, :appId, :cutoff, :horizon, :model,
                :status, :stage, :created, :updated, :deadline, :next)
                """, parameters(task));
    }

    Optional<PredictionTask> find(String taskId) {
        return first(jdbc.query("SELECT * FROM prediction_task WHERE task_id = :id", new MapSqlParameterSource("id", taskId), rowMapper));
    }

    Optional<PredictionTask> findByKey(String hash) {
        return first(jdbc.query("SELECT * FROM prediction_task WHERE idempotency_key_hash = :key", new MapSqlParameterSource("key", hash), rowMapper));
    }

    List<PredictionTask> due(Instant now) {
        return jdbc.query("""
                SELECT * FROM prediction_task WHERE status IN ('PENDING', 'RUNNING')
                AND (next_attempt_at_ms <= :now OR deadline_at_ms <= :now)
                ORDER BY created_at_ms, task_id LIMIT 20
                """, new MapSqlParameterSource("now", now.toEpochMilli()), rowMapper);
    }

    void save(PredictionTask task) {
        jdbc.update("""
                UPDATE prediction_task SET status=:status, stage=:stage, dataset_source=:source, python_status=:pythonStatus,
                python_run_id=:pythonRun, error_code=:errorCode, error_message=:errorMessage,
                dependency_error_code=:dependencyCode, dependency_error_message=:dependencyMessage,
                attempt_count=:attempts, retry_count=:retries, updated_at_ms=:updated, next_attempt_at_ms=:next
                WHERE task_id=:id AND status IN ('PENDING', 'RUNNING')
                """, parameters(task));
    }

    private MapSqlParameterSource parameters(PredictionTask t) {
        return new MapSqlParameterSource().addValue("id", t.taskId).addValue("keyHash", t.keyHash).addValue("requestHash", t.requestHash)
                .addValue("datasetId", t.request.datasetId()).addValue("appId", t.request.appId()).addValue("cutoff", t.request.cutoffDate())
                .addValue("horizon", t.request.horizonDays()).addValue("model", t.request.modelVersion()).addValue("status", t.status)
                .addValue("stage", t.stage).addValue("created", t.createdAt.toEpochMilli()).addValue("updated", t.updatedAt.toEpochMilli())
                .addValue("deadline", t.deadlineAt.toEpochMilli()).addValue("next", t.nextAttemptAt.toEpochMilli())
                .addValue("source", t.datasetSource).addValue("pythonStatus", t.pythonStatus).addValue("pythonRun", t.pythonRunId)
                .addValue("errorCode", t.error == null ? null : t.error.code()).addValue("errorMessage", t.error == null ? null : t.error.message())
                .addValue("dependencyCode", t.dependencyError == null ? null : t.dependencyError.code())
                .addValue("dependencyMessage", t.dependencyError == null ? null : t.dependencyError.message())
                .addValue("attempts", t.attemptCount).addValue("retries", t.retryCount);
    }

    private static Optional<PredictionTask> first(List<PredictionTask> tasks) { return tasks.stream().findFirst(); }

    private static final RowMapper<PredictionTask> rowMapper = (rs, index) -> {
        PredictionTask t = new PredictionTask();
        t.taskId = rs.getString("task_id"); t.keyHash = rs.getString("idempotency_key_hash"); t.requestHash = rs.getString("request_hash");
        t.request = new PredictionRequest(rs.getString("dataset_id"), rs.getString("app_id"), rs.getDate("cutoff_date").toLocalDate(),
                rs.getInt("horizon_days"), rs.getString("model_version"));
        t.status = rs.getString("status"); t.stage = rs.getString("stage"); t.datasetSource = rs.getString("dataset_source");
        t.pythonStatus = rs.getString("python_status"); t.pythonRunId = rs.getString("python_run_id");
        t.error = rs.getString("error_code") == null ? null : new PredictionProblem.Error(rs.getString("error_code"), rs.getString("error_message"));
        t.dependencyError = rs.getString("dependency_error_code") == null ? null
                : new PredictionProblem.Error(rs.getString("dependency_error_code"), rs.getString("dependency_error_message"));
        t.attemptCount = rs.getInt("attempt_count"); t.retryCount = rs.getInt("retry_count");
        t.createdAt = Instant.ofEpochMilli(rs.getLong("created_at_ms")); t.updatedAt = Instant.ofEpochMilli(rs.getLong("updated_at_ms"));
        t.deadlineAt = Instant.ofEpochMilli(rs.getLong("deadline_at_ms")); t.nextAttemptAt = Instant.ofEpochMilli(rs.getLong("next_attempt_at_ms"));
        return t;
    };
}
