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
    void pythonFailureIsPersistedButPythonSuccessCannotBecomeBusinessSuccessInStepThree() {
        var failed = service.submit(request(), "fail");
        python.taskStatus = "FAILED";
        worker.tick();
        assertEquals("FAILED", service.get(failed.taskId()).status());
        assertEquals("MODEL_NOT_IMPLEMENTED", service.get(failed.taskId()).error().code());
        var computed = service.submit(request(), "computed");
        python.taskStatus = "SUCCEEDED";
        worker.tick();
        var task = service.get(computed.taskId());
        assertEquals("FAILED", task.status());
        assertEquals("AGGREGATION_NOT_IMPLEMENTED", task.error().code());
        assertNull(task.runId());
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
        String taskStatus;
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
                    taskStatus.equals("SUCCEEDED") ? "run_fixture" : null,
                    taskStatus.equals("FAILED") ? new PredictionProblem.Error("MODEL_NOT_IMPLEMENTED", "真实模型未实现") : null));
        }

        public PythonTask submit(String id, PredictionRequest request) {
            calls++;
            submittedIds.add(id);
            submittedRequest = request;
            if (submitFailure != null) throw submitFailure;
            return new PythonTask(id, "PENDING", null, null);
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
