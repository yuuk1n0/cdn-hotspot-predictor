package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PredictionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private static final String TASK_SELECT = "SELECT t.*, r.run_id AS business_run_id, r.prediction_source AS result_source "
            + "FROM prediction_task t LEFT JOIN prediction_run r ON r.task_id=t.task_id ";

    public PredictionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
    }

    void insert(PredictionTask task) {
        jdbc.update("""
                INSERT INTO prediction_task (task_id, idempotency_key_hash, request_hash, dataset_id, app_id, cutoff_date,
                horizon_days, model_version, status, stage, created_at_ms, updated_at_ms, deadline_at_ms, next_attempt_at_ms)
                VALUES (:id, :keyHash, :requestHash, :datasetId, :appId, :cutoff, :horizon, :model,
                :status, :stage, :created, :updated, :deadline, :next)
                """, parameters(task));
    }

    Optional<PredictionTask> find(String taskId) {
        return first(jdbc.query(TASK_SELECT + "WHERE t.task_id = :id", new MapSqlParameterSource("id", taskId), rowMapper));
    }

    Optional<PredictionTask> findByKey(String hash) {
        return first(jdbc.query(TASK_SELECT + "WHERE t.idempotency_key_hash = :key", new MapSqlParameterSource("key", hash), rowMapper));
    }

    List<PredictionTask> due(Instant now) {
        return jdbc.query(TASK_SELECT + """
                WHERE t.status IN ('PENDING', 'RUNNING')
                AND (t.next_attempt_at_ms <= :now OR t.deadline_at_ms <= :now)
                ORDER BY t.created_at_ms, t.task_id LIMIT 20
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

    Optional<String> snapshot(String runId) {
        var values = jdbc.query("""
                SELECT r.snapshot_json FROM prediction_run r JOIN prediction_task t ON t.task_id=r.task_id
                WHERE r.run_id=:runId AND t.status='SUCCEEDED' AND t.python_run_id=r.run_id
                """, new MapSqlParameterSource("runId", runId), (rs, index) -> rs.getString(1));
        return values.stream().findFirst();
    }

    void publish(PredictionTask task, JsonNode snapshot, Clock clock) {
        try {
            transactions.executeWithoutResult(transaction -> {
                var p = new MapSqlParameterSource("id", task.taskId).addValue("run", task.pythonRunId)
                        .addValue("source", snapshot.path("predictionSource").textValue()).addValue("snapshot", snapshot.toString());
                var locked = jdbc.queryForMap("SELECT status, deadline_at_ms FROM prediction_task WHERE task_id=:id FOR UPDATE", p);
                if ("SUCCEEDED".equals(locked.get("status"))) {
                    Integer owned = jdbc.queryForObject("SELECT COUNT(*) FROM prediction_run WHERE run_id=:run AND task_id=:id", p, Integer.class);
                    if (owned == null || owned != 1) throw new PredictionProblem(422, "INVALID_RESULT", "已完成任务的运行关联不一致。");
                    return;
                }
                if (!List.of("PENDING", "RUNNING").contains(locked.get("status"))) throw new PredictionProblem(409, "TASK_NOT_ACTIVE", "任务已经终结，不能发布迟到榜单。");
                long deadline = ((Number) locked.get("deadline_at_ms")).longValue();
                checkDeadline(deadline, clock);
                p.addValue("now", clock.instant().toEpochMilli());
                jdbc.update("""
                        INSERT INTO prediction_run (run_id, task_id, prediction_source, snapshot_json, created_at_ms)
                        VALUES (:run, :id, :source, :snapshot, :now)
                        """, p);
                checkDeadline(deadline, clock);
                jdbc.update("""
                        UPDATE prediction_task SET status='SUCCEEDED', stage='COMPLETED', error_code=NULL, error_message=NULL,
                        dependency_error_code=NULL, dependency_error_message=NULL, retry_count=0, updated_at_ms=:now
                        WHERE task_id=:id
                        """, p);
            });
        } catch (DuplicateKeyException conflict) {
            throw new PredictionProblem(422, "INVALID_RESULT", "运行编号或任务已有榜单；不会覆盖已有快照。");
        }
        task.status = "SUCCEEDED"; task.stage = "COMPLETED";
        task.businessRunId = task.pythonRunId; task.predictionSource = snapshot.path("predictionSource").textValue();
    }

    private static void checkDeadline(long deadline, Clock clock) {
        if (clock.instant().toEpochMilli() >= deadline) throw new PredictionProblem(422, "TASK_TIMEOUT", "榜单事务发布前已超过任务总时限。");
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
        t.businessRunId = rs.getString("business_run_id"); t.predictionSource = rs.getString("result_source");
        t.error = rs.getString("error_code") == null ? null : new PredictionProblem.Error(rs.getString("error_code"), rs.getString("error_message"));
        t.dependencyError = rs.getString("dependency_error_code") == null ? null
                : new PredictionProblem.Error(rs.getString("dependency_error_code"), rs.getString("dependency_error_message"));
        t.attemptCount = rs.getInt("attempt_count"); t.retryCount = rs.getInt("retry_count");
        t.createdAt = Instant.ofEpochMilli(rs.getLong("created_at_ms")); t.updatedAt = Instant.ofEpochMilli(rs.getLong("updated_at_ms"));
        t.deadlineAt = Instant.ofEpochMilli(rs.getLong("deadline_at_ms")); t.nextAttemptAt = Instant.ofEpochMilli(rs.getLong("next_attempt_at_ms"));
        return t;
    };
}
