package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PredictionFlowTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private PredictionRepository repository;
    private PredictionService service;
    private PredictionWorker worker;
    private FakePython python;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setup() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("prediction-test-schema.sql")).execute(dataSource);
        wire(dataSource);
    }

    private void wire(DriverManagerDataSource source) {
        repository = new PredictionRepository(new NamedParameterJdbcTemplate(source));
        python = new FakePython();
        PredictionSettings settings = new PredictionSettings("http://python.test", Duration.ofSeconds(3), Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(180), Duration.ofSeconds(10));
        service = new PredictionService(repository, mapper, clock, settings);
        worker = new PredictionWorker(repository, python, settings, clock);
    }

    private PredictionRequest request() {
        return new PredictionRequest("ds_fixture", "10350", LocalDate.of(2026, 10, 6), 7, "contract-demo-v1");
    }

    @Test
    void acceptsDurablyBeforeCallingPythonAndKeepsAllRequestMetadata() {
        var accepted = service.submit(request(), "one");
        var task = service.get(accepted.taskId());
        assertEquals("PENDING", task.status());
        assertEquals("ds_fixture", task.datasetId());
        assertEquals("10350", task.appId());
        assertEquals(7, task.horizonDays());
        assertNull(task.runId());
        assertEquals(0, python.calls);
        assertEquals(accepted.taskId(), new PredictionService(repository, mapper, clock, settings()).get(accepted.taskId()).taskId());
    }

    private PredictionSettings settings() {
        return new PredictionSettings("http://python.test", Duration.ofSeconds(3), Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(180), Duration.ofSeconds(10));
    }

    @Test
    void idempotencyIsPersistedAndConflictingRequestsAreRejected() {
        var first = service.submit(request(), "one");
        assertEquals(first.taskId(), service.submit(request(), "one").taskId());
        PredictionRequest different = new PredictionRequest("ds_fixture", "10350", LocalDate.of(2026, 10, 6), 1, "contract-demo-v1");
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(PredictionProblem.class, () -> service.submit(different, "one")).code());
    }

    @Test
    void concurrentSameKeyCreatesOneTask() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> service.submit(request(), "concurrent").taskId()));
            var ids = new java.util.HashSet<String>();
            for (var future : futures) ids.add(future.get());
            assertEquals(1, ids.size());
            assertEquals(1L, new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM prediction_task", Long.class));
        }
    }

    @Test
    void workerValidatesDatasetAndForwardsOriginalTaskIdWithoutSelectingMockByModelName() {
        var accepted = service.submit(request(), "one");
        worker.tick();
        var task = service.get(accepted.taskId());
        assertEquals(List.of(accepted.taskId()), python.submittedIds);
        assertEquals(request(), python.submittedRequest);
        assertEquals("RUNNING", task.status());
        assertEquals("COMPUTING", task.stage());
        assertEquals("REAL", task.datasetSource());
        assertNull(task.error());
        assertNull(task.runId());
    }

    @Test
    void wrongAppInvalidDatasetAndOutOfRangeDateFailBeforeDispatch() {
        for (String scenario : List.of("missing", "app", "cutoff", "type", "unit", "source", "not-ready")) {
            python = new FakePython();
            if (scenario.equals("missing")) python.datasetFailure = new PredictionProblem(404, "DATASET_NOT_FOUND", "不存在");
            if (scenario.equals("app")) python.dataset.putArray("appIds").add("10066");
            if (scenario.equals("cutoff")) ((ObjectNode) python.dataset.path("appDateRanges").path("10350")).put("end", "2026-10-05");
            if (scenario.equals("type")) python.dataset.put("datasetType", "APP_DAILY_TOTAL");
            if (scenario.equals("unit")) python.dataset.put("sourceUnit", "UNKNOWN");
            if (scenario.equals("source")) python.dataset.put("datasetSource", "UNKNOWN");
            if (scenario.equals("not-ready")) python.dataset.put("status", "PENDING");
            worker = new PredictionWorker(repository, python, settings(), clock);
            var accepted = service.submit(request(), scenario);
            worker.tick();
            var task = service.get(accepted.taskId());
            assertEquals("FAILED", task.status(), scenario);
            assertNotNull(task.error(), scenario);
            assertTrue(python.submittedIds.isEmpty(), scenario);
        }
    }

    @Test
    void temporaryNetworkFailureRetriesOriginalTaskAndDoesNotBecomeAlgorithmFailure() {
        var accepted = service.submit(request(), "one");
        python.submitFailure = PredictionProblem.retryable("PYTHON_UNAVAILABLE", "暂时断开");
        worker.tick();
        var waiting = service.get(accepted.taskId());
        assertEquals("RUNNING", waiting.status());
        assertEquals("WAITING_DEPENDENCY", waiting.stage());
        assertNull(waiting.error());
        assertEquals("PYTHON_UNAVAILABLE", waiting.lastDependencyError().code());
        worker.tick();
        assertEquals(1, python.submittedIds.size());
        clock.advance(Duration.ofSeconds(2));
        python.submitFailure = null;
        worker.tick();
        assertEquals(List.of(accepted.taskId(), accepted.taskId()), python.submittedIds);
        assertNull(service.get(accepted.taskId()).lastDependencyError());
    }

    @Test
    void datasetIdsMustRemainStringsAcrossThePythonBoundary() {
        python.dataset.put("datasetId", 123);
        var request = new PredictionRequest("123", "10350", LocalDate.of(2026, 10, 6), 1, "contract-demo-v1");
        var accepted = service.submit(request, "numeric-metadata");
        worker.tick();
        assertEquals("FAILED", service.get(accepted.taskId()).status());
        assertEquals("INVALID_DATASET", service.get(accepted.taskId()).error().code());
        assertTrue(python.submittedIds.isEmpty());
    }

    @Test
    void aLostSubmissionResponseIsRecoveredByQueryingPythonInsteadOfResubmitting() {
        var accepted = service.submit(request(), "one");
        python.submitFailure = PredictionProblem.retryable("PYTHON_UNAVAILABLE", "响应丢失");
        worker.tick();
        python.taskStatus = "RUNNING";
        clock.advance(Duration.ofSeconds(2));
        worker.tick();
        assertEquals(1, python.submittedIds.size());
        assertEquals("RUNNING", service.get(accepted.taskId()).status());
    }

    @Test
    void totalDeadlineSurvivesRetryAndProducesExplicitTimeout() {
        var accepted = service.submit(request(), "one");
        python.submitFailure = PredictionProblem.retryable("PYTHON_API_UNAVAILABLE", "预测接口尚未开放");
        worker.tick();
        clock.advance(Duration.ofSeconds(181));
        worker.tick();
        assertEquals("FAILED", service.get(accepted.taskId()).status());
        assertEquals("TASK_TIMEOUT", service.get(accepted.taskId()).error().code());
        assertNull(service.get(accepted.taskId()).runId());
    }

    @Test
    void aHundredSecondComputationUsesTheSamePollingContract() {
        var accepted = service.submit(request(), "one");
        worker.tick();
        python.taskStatus = "RUNNING";
        clock.advance(Duration.ofSeconds(100));
        worker.tick();
        assertEquals("RUNNING", service.get(accepted.taskId()).status());
        assertEquals(1, python.submittedIds.size());
        assertNull(service.get(accepted.taskId()).error());
    }

    @Test
    void pythonFailureIsPersistedAndSuccessRequiresAQueryableBusinessSnapshot() {
        var failed = service.submit(request(), "fail");
        python.taskStatus = "FAILED";
        worker.tick();
        assertEquals("FAILED", service.get(failed.taskId()).status());
        assertEquals("MODEL_NOT_IMPLEMENTED", service.get(failed.taskId()).error().code());
        var computed = service.submit(request(), "computed");
        python.taskStatus = "SUCCEEDED";
        worker.tick();
        var task = service.get(computed.taskId());
        assertEquals("SUCCEEDED", task.status());
        assertNull(task.error());
        assertEquals("run_fixture", task.runId());
        assertEquals("MOCK", task.predictionSource());
        var snapshot = service.result(task.runId());
        assertEquals(2, snapshot.path("top50Count").intValue());
        assertEquals("20000000000000002", snapshot.path("top50PredictedBytes").textValue());
        assertEquals(computed.taskId(), snapshot.path("taskId").textValue());
        int calls = python.calls;
        worker.tick();
        assertEquals(calls, python.calls);
        assertEquals(snapshot, service.result(task.runId()));
        assertEquals(1L, new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM prediction_run", Long.class));
    }

    @Test
    void resultNetworkFailureRetriesTheOriginalRunWithoutCreatingNewPrediction() {
        var accepted = service.submit(request(), "results-retry");
        python.taskStatus = "SUCCEEDED";
        python.itemsFailure = PredictionProblem.retryable("PYTHON_UNAVAILABLE", "结果暂时不可读");
        worker.tick();
        var waiting = service.get(accepted.taskId());
        assertEquals("RUNNING", waiting.status());
        assertEquals("WAITING_DEPENDENCY", waiting.stage());
        assertNull(waiting.runId());
        python.itemsFailure = null;
        clock.advance(Duration.ofSeconds(2));
        worker.tick();
        assertEquals("SUCCEEDED", service.get(accepted.taskId()).status());
        assertEquals(List.of("run_fixture", "run_fixture"), python.queriedRuns);
        assertTrue(python.submittedIds.isEmpty());
    }

    @Test
    void repeatedResultFailuresKeepExponentialBackoffAndCannotChangeTheRunReference() {
        var accepted = service.submit(request(), "backoff");
        python.taskStatus = "SUCCEEDED";
        python.itemsFailure = PredictionProblem.retryable("PYTHON_UNAVAILABLE", "暂时不可用");
        worker.tick();
        clock.advance(Duration.ofSeconds(2));
        worker.tick();
        assertEquals(clock.instant().plusSeconds(4), service.get(accepted.taskId()).nextAttemptAt());
        assertEquals(2, python.queriedRuns.size());
        python.resultRunId = "changed-run";
        clock.advance(Duration.ofSeconds(4));
        worker.tick();
        assertEquals("FAILED", service.get(accepted.taskId()).status());
        assertEquals("INVALID_RESULT", service.get(accepted.taskId()).error().code());
        assertEquals(2, python.queriedRuns.size());
    }

    @Test
    void snapshotInsertAndTaskSuccessRollbackTogetherAndCanRetry() {
        var accepted = service.submit(request(), "atomic");
        python.taskStatus = "SUCCEEDED";
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE prediction_task ADD CONSTRAINT fail_publication CHECK (status <> 'SUCCEEDED')");
        worker.tick();
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM prediction_run", Long.class));
        assertEquals("RUNNING", service.get(accepted.taskId()).status());
        assertNull(service.get(accepted.taskId()).runId());
        jdbc.execute("ALTER TABLE prediction_task DROP CONSTRAINT fail_publication");
        clock.advance(Duration.ofSeconds(2));
        worker.tick();
        assertEquals("SUCCEEDED", service.get(accepted.taskId()).status());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM prediction_run", Long.class));
    }

    @Test
    void aDifferentRunCannotOverwriteAnEarlierValidSnapshotAndBadResultFailsExplicitly() {
        var first = service.submit(request(), "valid");
        python.taskStatus = "SUCCEEDED";
        worker.tick();
        var original = service.result(service.get(first.taskId()).runId());
        var second = service.submit(request(), "conflicting-run");
        worker.tick();
        assertEquals("FAILED", service.get(second.taskId()).status());
        assertEquals("INVALID_RESULT", service.get(second.taskId()).error().code());
        assertEquals(original, service.result("run_fixture"));
    }

    @Test
    void reenteringPublicationMustStillVerifyTheRunBelongsToTheLockedTask() {
        var first = service.submit(request(), "first");
        python.taskStatus = "SUCCEEDED";
        worker.tick();
        service.submit(request(), "second");
        python.resultRunId = "run_second";
        worker.tick();
        var forged = repository.find(first.taskId()).orElseThrow();
        forged.pythonRunId = "run_second";
        var snapshot = PredictionResult.build(PredictionResultTest.page(forged, 2), forged);
        assertEquals("INVALID_RESULT", assertThrows(PredictionProblem.class, () -> repository.publish(forged, snapshot, clock)).code());
        assertEquals("run_fixture", service.get(first.taskId()).runId());
    }

    @Test
    void lateOrMalformedResultNeverPublishesBusinessSuccess() {
        var accepted = service.submit(request(), "late");
        python.taskStatus = "SUCCEEDED";
        python.onItems = () -> clock.advance(Duration.ofSeconds(181));
        worker.tick();
        assertEquals("FAILED", service.get(accepted.taskId()).status());
        assertEquals("TASK_TIMEOUT", service.get(accepted.taskId()).error().code());
        assertNull(service.get(accepted.taskId()).runId());
        assertEquals(0L, new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM prediction_run", Long.class));
    }

    @Test
    void historicalStoredSnapshotWithoutResultModeRemainsReadable() {
        var accepted = service.submit(request(), "legacy-snapshot");
        python.taskStatus = "SUCCEEDED";
        worker.tick();
        var historical = (ObjectNode) service.result("run_fixture");
        historical.remove("resultMode");
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "UPDATE prediction_run SET snapshot_json = ? WHERE run_id = ?", historical.toString(), "run_fixture");
        assertEquals(historical, service.result("run_fixture"));
        assertFalse(service.result("run_fixture").has("resultMode"));
        assertEquals("MOCK", service.get(accepted.taskId()).resultMode());
    }

    @Test
    void successSnapshotSurvivesFileDatabaseReopenAndPythonGoingOffline(@TempDir Path directory) {
        var source = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("snapshot") + ";MODE=MySQL", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("prediction-test-schema.sql")).execute(source);
        wire(source);
        var accepted = service.submit(request(), "snapshot-restart");
        python.taskStatus = "SUCCEEDED";
        worker.tick();
        var result = service.result(service.get(accepted.taskId()).runId());
        wire(new DriverManagerDataSource(source.getUrl(), "sa", ""));
        python.datasetFailure = PredictionProblem.retryable("PYTHON_UNAVAILABLE", "offline");
        assertEquals("SUCCEEDED", service.get(accepted.taskId()).status());
        assertEquals(result, service.result("run_fixture"));
        worker.tick();
        assertEquals(0, python.calls);
    }

    @Test
    void fileDatabaseSurvivesClosingAndReopeningTheConnection(@TempDir Path directory) {
        String url = "jdbc:h2:file:" + directory.resolve("tasks") + ";MODE=MySQL";
        var first = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("prediction-test-schema.sql")).execute(first);
        wire(first);
        var accepted = service.submit(request(), "restart");
        worker.tick();
        var reopened = new DriverManagerDataSource(url, "sa", "");
        wire(reopened);
        python.taskStatus = "RUNNING";
        clock.advance(Duration.ofSeconds(2));
        worker.tick();
        assertEquals(accepted.taskId(), service.submit(request(), "restart").taskId());
        assertEquals("RUNNING", service.get(accepted.taskId()).status());
        assertTrue(python.submittedIds.isEmpty());
    }

    private class FakePython implements PredictorClient {
        int calls;
        ObjectNode dataset = mapper.valueToTree(java.util.Map.of(
                "datasetId", "ds_fixture", "status", "READY", "datasetType", "FILE_DAILY", "datasetSource", "REAL", "sourceUnit", "B",
                "appIds", List.of("10350"), "appDateRanges", java.util.Map.of("10350", java.util.Map.of("start", "2026-01-05", "end", "2026-10-13"))));
        PredictionProblem datasetFailure;
        PredictionProblem submitFailure;
        PredictionProblem itemsFailure;
        Runnable onItems = () -> {};
        List<String> queriedRuns = new ArrayList<>();
        String taskStatus;
        String resultRunId = "run_fixture";
        List<String> submittedIds = new ArrayList<>();
        PredictionRequest submittedRequest;

        public com.fasterxml.jackson.databind.JsonNode dataset(String id) {
            calls++;
            if (datasetFailure != null) throw datasetFailure;
            return dataset;
        }

        public java.util.Optional<PythonTask> task(String id) {
            calls++;
            return taskStatus == null ? java.util.Optional.empty() : java.util.Optional.of(new PythonTask(id, taskStatus,
                    taskStatus.equals("SUCCEEDED") ? resultRunId : null,
                    taskStatus.equals("FAILED") ? new PredictionProblem.Error("MODEL_NOT_IMPLEMENTED", "真实模型未实现") : null));
        }

        public PythonTask submit(String id, PredictionRequest request) {
            calls++;
            submittedIds.add(id);
            submittedRequest = request;
            if (submitFailure != null) throw submitFailure;
            return new PythonTask(id, "PENDING", null, null);
        }

        public com.fasterxml.jackson.databind.JsonNode items(String runId) {
            calls++; queriedRuns.add(runId); onItems.run();
            if (itemsFailure != null) throw itemsFailure;
            var task = PredictionResultTest.task();
            task.taskId = repository.due(clock.instant()).get(0).taskId;
            task.request = request(); task.pythonRunId = runId;
            return PredictionResultTest.page(task, 2);
        }
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T08:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
