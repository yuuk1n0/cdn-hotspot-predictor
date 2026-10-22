package com.yukino.cdn.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsService;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NotificationServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final MutableClock clock = new MutableClock();
    final Ops ops = new Ops();
    NotificationRepository repository;
    NotificationService service;
    int calls;
    NotificationTransport.Outcome outcome = NotificationTransport.Outcome.sent();

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new NamedParameterJdbcTemplate(source);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE notification_delivery (
                id VARCHAR(64) PRIMARY KEY, app_id VARCHAR(64) NOT NULL, run_id VARCHAR(128), type VARCHAR(16) NOT NULL,
                purpose VARCHAR(16) NOT NULL, evidence_source VARCHAR(16) NOT NULL, destination_id VARCHAR(64) NOT NULL,
                business_key_hash CHAR(64) NOT NULL UNIQUE, idempotency_key_hash CHAR(64) NOT NULL UNIQUE,
                request_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, record_json LONGTEXT NOT NULL,
                attempt_count INT NOT NULL, next_attempt_at_ms BIGINT NOT NULL, created_at_ms BIGINT NOT NULL,
                updated_at_ms BIGINT NOT NULL, sent_at_ms BIGINT)
                """);
        repository = new NotificationRepository(jdbc, mapper);
        service = new NotificationService(repository, ops, settings(), (destination, text) -> { calls++; return outcome; }, mapper, clock);
    }
    NotificationSettings settings() {
        return new NotificationSettings("{\"test\":{\"webhook\":\"http://127.0.0.1:12345/hook\",\"secret\":\"secret-value\",\"label\":\"测试群\",\"demoOnly\":true},\"business\":{\"webhook\":\"http://127.0.0.1:12345/hook\",\"label\":\"业务群\",\"demoOnly\":false}}", true, mapper);
    }
    ObjectNode request() {
        return mapper.createObjectNode().put("appId", "10350").put("runId", "run_1").put("type", "DAILY")
                .put("purpose", "DEMO").put("evidenceSource", "TEST").put("destinationId", "test");
    }
    @Test void previewUsesPendingAndDoesNotSendAndLimitsTextToTenItems() {
        var result = service.preview("10350", "run_1", "DAILY", "DEMO", "TEST");
        assertEquals(12, result.path("itemCount").asInt());
        assertEquals(0, calls);
        assertTrue(result.path("text").asText().contains("DEMO"));
        assertTrue(result.path("text").asText().contains("共12项"));
        assertFalse(result.path("text").asText().contains("file-11"));
        assertTrue(result.path("text").asText().contains("2026-10-20"));
        assertTrue(result.path("text").asText().contains("/predictions/run_1"));
    }
    @Test void receiptIsDurableAndBusinessAndIdempotencyKeysPreventSecondSend() {
        var first = service.submit(request(), "one");
        assertEquals("PENDING", first.path("status").asText());
        assertEquals(0, calls);
        assertEquals(first, service.submit(request(), "one"));
        assertEquals(first, service.submit(request(), "different-key"));
        service.processDue(); service.processDue();
        assertEquals(1, calls);
        var row = service.list("10350", null, null, 1, 20).path("items").get(0);
        assertEquals("SENT", row.path("status").asText());
        assertEquals(1, row.path("attemptCount").asInt());
        assertFalse(row.toString().contains("secret-value"));
        assertFalse(row.toString().contains("127.0.0.1"));
        var monitor = service.monitor("10350");
        assertEquals(1, monitor.path("total").asInt());
        assertEquals(1, monitor.path("statusCounts").path("SENT").asInt());
        assertFalse(monitor.toString().contains("secret-value"));
        assertFalse(monitor.toString().contains("127.0.0.1"));
        var changed = request().put("type", "OVERDUE");
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(PredictionProblem.class, () -> service.submit(changed, "one")).code());
    }
    @Test void rejectsWrongAppAndBusinessMockOrHistoricalAndTestDestination() {
        assertEquals(422, assertThrows(PredictionProblem.class, () -> service.preview("other", "run_1", "DAILY", "DEMO", "TEST")).httpStatus());
        assertFalse(service.preview("10350", "run_1", "DAILY", "BUSINESS", "REAL").path("canSend").asBoolean());
        assertEquals("SOURCE_NOT_ELIGIBLE", assertThrows(PredictionProblem.class, () -> service.submit(request().put("purpose", "BUSINESS"), "bad")).code());
        assertEquals("DEMO_DESTINATION_REQUIRED", assertThrows(PredictionProblem.class, () -> service.submit(request().put("destinationId", "business"), "bad2")).code());
    }
    @Test void sourceIsRecheckedImmediatelyBeforeActualSend() {
        service.submit(request(), "one");
        ops.empty = true;
        service.processDue();
        assertEquals(0, calls);
        assertEquals("SKIPPED", service.list("10350", null, null, 1, 20).path("items").get(0).path("status").asText());
    }
    @Test void unknownIsNeverAutomaticallyRetriedAndRestartRecoversSendingAsUnknown() {
        outcome = NotificationTransport.Outcome.unknown("DELIVERY_UNCONFIRMED");
        service.submit(request(), "one"); service.processDue();
        clock.now = clock.now.plus(Duration.ofDays(1)); service.processDue();
        assertEquals(1, calls);
        assertEquals("UNKNOWN", service.list("10350", null, null, 1, 20).path("items").get(0).path("status").asText());
        var another = service.submit(request().put("type", "OVERDUE"), "two");
        assertTrue(repository.claim(another.path("notificationId").asText(), clock.instant()));
        service.recoverInterrupted();
        assertEquals("UNKNOWN", repository.find(another.path("notificationId").asText()).orElseThrow().path("status").asText());
    }
    @Test void onlyExplicitRetryableFailuresRetryAtOneFiveFifteenMinutesFourAttemptsMaximum() {
        outcome = NotificationTransport.Outcome.retryable("CONNECTION_NOT_ESTABLISHED");
        service.submit(request(), "retry"); service.processDue();
        service.processDue(); assertEquals(1, calls);
        clock.now = clock.now.plusSeconds(59); service.processDue(); assertEquals(1, calls);
        clock.now = clock.now.plusSeconds(1); service.processDue(); assertEquals(2, calls);
        clock.now = clock.now.plusSeconds(300); service.processDue(); assertEquals(3, calls);
        clock.now = clock.now.plusSeconds(900); service.processDue(); assertEquals(4, calls);
        clock.now = clock.now.plusSeconds(9000); service.processDue(); assertEquals(4, calls);
        assertEquals("FAILED", service.list("10350", null, null, 1, 20).path("items").get(0).path("status").asText());
    }
    @Test void automaticDefaultsOffAndPersistsSkippedWhenCurrentRunIsUnavailable() {
        service.automatic("DAILY"); assertEquals(0, service.list("10350", null, null, 1, 20).path("total").asInt());
        ops.enabled = true; ops.noRun = true;
        service.automatic("DAILY"); service.automatic("DAILY");
        var list = service.list("10350", null, null, 1, 20);
        assertEquals(1, list.path("total").asInt());
        assertEquals("SKIPPED", list.path("items").get(0).path("status").asText());
        assertEquals("NO_CURRENT_RUN", list.path("items").get(0).path("lastError").asText());
        assertEquals(0, calls);
    }
    @Test void automaticRequiresCurrentConfiguredRealModelAndReusesManualBusinessKey() {
        ops.enabled = true; ops.current = true;
        var body = request().put("purpose", "BUSINESS").put("evidenceSource", "REAL").put("destinationId", "business");
        var manual = service.submit(body, "manual");
        service.automatic("DAILY");
        assertEquals(1, service.list("10350", null, null, 1, 20).path("total").asInt());
        service.processDue(); assertEquals(1, calls);
        assertEquals(manual.path("notificationId").asText(), service.list("10350", null, null, 1, 20).path("items").get(0).path("notificationId").asText());
    }
    @Test void hourlyOverdueCanRemindForYesterdaysRealRunAfterItsDeadline() {
        ops.enabled = true; ops.historic = true;
        service.automatic("OVERDUE");
        service.processDue();
        assertEquals(1, calls);
        var record = service.list("10350", "run_1", "OVERDUE", 1, 20).path("items").get(0);
        assertEquals("SENT", record.path("status").asText());
        assertEquals("2026-10-20", record.path("businessDate").asText());
        service.automatic("OVERDUE"); service.processDue();
        assertEquals(1, calls);
    }
    @Test void destinationsAndErrorsNeverExposeCredentialsAndMissingConfigDoesNotSend() {
        assertFalse(service.destinations().toString().contains("webhook"));
        assertFalse(service.destinations().toString().contains("secret"));
        assertEquals("DESTINATION_NOT_CONFIGURED", assertThrows(PredictionProblem.class, () -> service.submit(request().put("destinationId", "missing"), "x")).code());
        assertEquals(0, calls);
    }
    @Test void overdueDelegatesRealScopeAndRejectsInvalidPagination() {
        assertEquals("REAL", service.overdue("10350", "run_1", "REAL").path("evidenceSource").asText());
        assertEquals(422, assertThrows(PredictionProblem.class, () -> service.list("10350", null, null, 0, 20)).httpStatus());
    }
    class Ops implements OperationsService {
        boolean enabled, noRun, current, empty, historic;
        public ObjectNode config(String appId) { return mapper.createObjectNode().put("notificationsEnabled", enabled).put("destinationId", "business").put("dailyModelVersion", "v1").put("handlingHours", 24); }
        public ObjectNode updateConfig(String appId, JsonNode body) { throw new UnsupportedOperationException(); }
        public List<String> configuredApps() { return List.of("10350"); }
        public List<String> reminderRuns(String appId) { return historic ? List.of("run_1") : List.of(); }
        public ObjectNode run(String appId, String runId, LocalDate businessDate, int horizonDays) {
            if (!"10350".equals(appId)) throw new PredictionProblem(422,"APP_MISMATCH","App mismatch");
            if (noRun) throw new PredictionProblem(404,"NO_CURRENT_RUN","No current run");
            if (historic && businessDate != null && !businessDate.equals(LocalDate.parse("2026-10-20")))
                throw new PredictionProblem(404,"NO_CURRENT_RUN","No current run");
            return mapper.createObjectNode().put("appId", appId).put("runId", "run_1").put("predictionStartDate", current ? "2026-10-21" : "2026-10-20")
                    .put("horizonDays", 1).put("modelVersion", "v1").put("datasetSource", current || historic ? "REAL" : "FAKE")
                    .put("predictionSource", current || historic ? "MODEL" : "MOCK");
        }
        public ObjectNode pending(String appId, String runId, String evidenceSource) {
            var node = run(appId, runId, null, 1).put("evidenceSource", evidenceSource);
            var items = node.putArray("items");
            if (!empty) for(int i=1;i<=12;i++) items.addObject().put("fileKey", "file-"+i).put("predictedBytes", "100").put("compressionStatus", "PENDING");
            return node;
        }
        public ObjectNode overdue(String appId, String runId, String evidenceSource) { return pending(appId, runId, evidenceSource).put("handlingHours",24).put("asOf", clock.instant().toString()); }
        public ObjectNode evidence(String tool, JsonNode context, JsonNode arguments) { throw new UnsupportedOperationException(); }
        public ObjectNode suggestion(String appId, int horizonDays, String backtestId) { throw new UnsupportedOperationException(); }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-21T01:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
