package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.operations.OperationsRepository;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertIncidentServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private AlertIncidentRepository repository;
    private AlertRouteService routes;
    private AlertIncidentService service;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var operations = new OperationsRepository(new NamedParameterJdbcTemplate(data), mapper);
        routes = new AlertRouteService(operations, mapper, clock);
        repository = new AlertIncidentRepository(new NamedParameterJdbcTemplate(data), mapper);
        service = new AlertIncidentService(repository, routes, mapper, clock);
    }

    @Test
    void sameFingerprintUpdatesOneIncidentAndRecoveryAllowsNewIncident() {
        var first = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));
        var second = service.ingest(AlertTestFixtures.alert(
                "evt-2", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:05:12Z"));

        assertEquals(first.path("incidentId").asText(), second.path("incidentId").asText());
        assertEquals(2, repository.get(first.path("incidentId").asText()).path("occurrenceCount").asInt());

        service.recover(first.path("incidentId").asText(), "ou_owner");
        var third = service.ingest(AlertTestFixtures.alert(
                "evt-3", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:10:12Z"));

        assertNotEquals(first.path("incidentId").asText(), third.path("incidentId").asText());
    }

    @Test
    void duplicateSourceEventReturnsOriginalIncidentWithoutAnotherOccurrence() {
        var first = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));
        var duplicate = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));

        assertEquals(first.path("incidentId").asText(), duplicate.path("incidentId").asText());
        assertTrue(duplicate.path("duplicate").asBoolean());
        assertEquals(1, repository.get(first.path("incidentId").asText()).path("occurrenceCount").asInt());
    }

    @Test
    void recoveryEventIsIdempotentAndStopsReminderSchedule() {
        var incident = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));
        String incidentId = incident.path("incidentId").asText();

        var recovered = service.ingest(AlertTestFixtures.recovery(
                "evt-recover-1", incidentId, "10350", "2026-10-22T06:08:00Z"));
        var repeated = service.ingest(AlertTestFixtures.recovery(
                "evt-recover-2", incidentId, "10350", "2026-10-22T06:09:00Z"));

        assertEquals("RECOVERED", recovered.path("status").asText());
        assertTrue(repeated.path("duplicate").asBoolean());
        assertFalse(repository.get(incidentId).hasNonNull("nextReminderAt"));
    }

    @Test
    void enabledRouteStartsPendingWhileDisabledRouteRecordsFailure() {
        var route = mapper.createObjectNode().put("expectedVersion", 0)
                .put("alertsEnabled", true).put("businessName", "内容分发")
                .put("fixedChatId", "oc_10350").put("testChatId", "oc_test_10350")
                .put("p1AutoCreateGroup", true)
                .put("ackDeadlineMinutes", 5).put("remindIntervalMinutes", 15)
                .put("maxReminderCount", 4).put("activeWindowMinutes", 60);
        route.putArray("ownerOpenIds").add("ou_owner");
        route.putArray("backupOpenIds").add("ou_backup");
        routes.update("10350", route);

        var enabled = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));
        var disabled = service.ingest(AlertTestFixtures.alert(
                "evt-2", "ALERT", "10066", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));

        assertEquals("PENDING", enabled.path("routingStatus").asText());
        assertEquals("ROUTING_FAILED", disabled.path("routingStatus").asText());
    }

    @Test
    void sameObjectAcrossAppsOrActiveWindowsCreatesSeparateIncidents() {
        var first = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));
        var otherApp = service.ingest(AlertTestFixtures.alert(
                "evt-2", "ALERT", "10066", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));
        var nextWindow = service.ingest(AlertTestFixtures.alert(
                "evt-3", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T07:04:12Z"));

        assertNotEquals(first.path("incidentId").asText(), otherApp.path("incidentId").asText());
        assertNotEquals(first.path("incidentId").asText(), nextWindow.path("incidentId").asText());
    }

    @Test
    void recoveryCannotChangeAnotherAppsIncident() {
        var incident = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z"));
        String incidentId = incident.path("incidentId").asText();

        var error = assertThrows(PredictionProblem.class, () -> service.ingest(
                AlertTestFixtures.recovery("evt-recover", incidentId, "10066", "2026-10-22T06:08:00Z")));

        assertEquals("APP_MISMATCH", error.code());
        assertEquals("OPEN", repository.get(incidentId).path("status").asText());
    }

    @Test
    void invalidEventDoesNotCreateIncident() {
        var invalid = AlertTestFixtures.alert(
                "evt-invalid", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z");
        invalid.put("severity", "P0");

        var error = assertThrows(PredictionProblem.class, () -> service.ingest(invalid));

        assertEquals("INVALID_ALERT_EVENT", error.code());
        assertEquals(0, service.list("10350", null, null, 1, 20).path("total").asInt());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-22T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
