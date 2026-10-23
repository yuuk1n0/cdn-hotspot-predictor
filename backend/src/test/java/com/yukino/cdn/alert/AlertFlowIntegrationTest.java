package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.operations.OperationsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertFlowIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private FakeAlertSender sender;
    private AlertIncidentRepository incidents;
    private AlertIncidentService service;
    private AlertReminderScheduler reminders;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var named = new NamedParameterJdbcTemplate(data);
        jdbc = new JdbcTemplate(data);
        var routes = new AlertRouteService(new OperationsRepository(named, mapper), mapper, clock);
        configure(routes, "10350", "oc_fixed_10350", true);
        configure(routes, "10066", "oc_fixed_10066", false);
        incidents = new AlertIncidentRepository(named, mapper);
        sender = new FakeAlertSender();
        var dispatcher = new AlertDispatcher(incidents, new AlertDeliveryRepository(named, mapper),
                routes, sender, new AlertCardRenderer(mapper), clock);
        service = new AlertIncidentService(incidents, routes, mapper, clock, dispatcher);
        reminders = new AlertReminderScheduler(incidents, routes, dispatcher, clock);
    }

    @Test
    void p1CreatesOneGroupAndRecoveryStopsReminders() {
        var first = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:00:00Z"));
        var update = service.ingest(AlertTestFixtures.alert(
                "evt-2", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:05:00Z"));

        assertEquals(first.path("incidentId").asText(), update.path("incidentId").asText());
        assertEquals(1, sender.createAttempts);
        assertEquals(1, sender.createdChats);
        clock.now = Instant.parse("2026-10-22T06:05:00Z");
        reminders.tick();
        assertEquals(1, sender.reminderCards);

        service.recover(first.path("incidentId").asText(), "ou_owner");
        clock.now = clock.now.plusSeconds(3600);
        reminders.tick();

        assertEquals("ROUTED", incidents.get(first.path("incidentId").asText()).path("routingStatus").asText());
        assertEquals(1, sender.reminderCards);
        assertTrue(sender.lastCard.contains("RECOVERY · RECOVERED"));
    }

    @Test
    void p2UsesItsOwnFixedGroupAndUnknownDeliveryNeverClaimsSent() {
        var p2 = service.ingest(AlertTestFixtures.alert(
                "evt-p2", "ALERT", "10066", "DATA_QUALITY", "file:1", "2026-10-22T06:00:00Z")
                .put("severity", "P2"));
        assertEquals(0, sender.createdChats);
        assertEquals("oc_fixed_10066", sender.lastChatId);

        sender.sendFails = true;
        var failed = service.ingest(AlertTestFixtures.alert(
                "evt-failed", "ALERT", "10350", "MODEL_FAILED", "run:1", "2026-10-22T07:00:00Z"));
        var stored = incidents.get(failed.path("incidentId").asText());
        assertEquals("ROUTING_FAILED", stored.path("routingStatus").asText());
        assertEquals("UNKNOWN", jdbc.queryForObject("""
                SELECT status FROM notification_delivery WHERE incident_id=? AND alert_phase='OPEN'
                """, String.class, failed.path("incidentId").asText()));
        assertEquals("P2", incidents.get(p2.path("incidentId").asText()).path("severity").asText());
    }

    @Test
    void deliveryRecordsAndResponsesDoNotExposeFeishuCredentials() {
        service.ingest(AlertTestFixtures.alert(
                "evt-secret", "ALERT", "10350", "IMPORT_FAILED", "task:secret", "2026-10-22T06:00:00Z"));

        String rows = jdbc.queryForList("SELECT record_json FROM notification_delivery", String.class).toString();
        assertFalse(rows.contains("app-secret"));
        assertFalse(rows.contains("tenant-token"));
        assertFalse(rows.contains("callback-token"));
        assertFalse(rows.contains("ingest-token"));
    }

    private void configure(AlertRouteService routes, String appId, String fixedChatId, boolean autoCreate) {
        var body = mapper.createObjectNode().put("expectedVersion", 0)
                .put("alertsEnabled", true).put("businessName", "业务 " + appId)
                .put("fixedChatId", fixedChatId).put("testChatId", "oc_test_" + appId)
                .put("p1AutoCreateGroup", autoCreate)
                .put("ackDeadlineMinutes", 5).put("remindIntervalMinutes", 15)
                .put("maxReminderCount", 4).put("activeWindowMinutes", 60);
        body.putArray("ownerOpenIds").add("ou_owner");
        body.putArray("backupOpenIds").add("ou_backup");
        routes.update(appId, body);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-22T06:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
