package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.operations.OperationsRepository;
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

class AlertReminderSchedulerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private AlertIncidentRepository repository;
    private AlertRouteService routes;
    private AlertIncidentService service;
    private AlertReminderScheduler scheduler;
    private FakeAlertSender sender;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var jdbc = new NamedParameterJdbcTemplate(data);
        routes = new AlertRouteService(new OperationsRepository(jdbc, mapper), mapper, clock);
        configure("10350");
        repository = new AlertIncidentRepository(jdbc, mapper);
        sender = new FakeAlertSender();
        var dispatcher = new AlertDispatcher(repository, new AlertDeliveryRepository(jdbc, mapper),
                routes, sender, new AlertCardRenderer(mapper), clock);
        service = new AlertIncidentService(repository, routes, mapper, clock, dispatcher);
        scheduler = new AlertReminderScheduler(repository, routes, dispatcher, clock);
    }

    @Test
    void reminderIsSentOncePerIntervalAndStopsAfterRecovery() {
        var incident = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1",
                "2026-10-22T06:00:00Z"));
        String incidentId = incident.path("incidentId").asText();

        clock.now = Instant.parse("2026-10-22T06:05:00Z");
        var due = repository.get(incidentId);
        assertEquals("OPEN", due.path("status").asText());
        assertEquals("2026-10-22T06:05:00Z", due.path("nextReminderAt").asText());
        scheduler.tick();
        var reminded = repository.get(incidentId);
        assertEquals(1, reminded.path("reminderCount").asInt());
        assertEquals("2026-10-22T06:20:00Z", reminded.path("nextReminderAt").asText());
        assertEquals(1, sender.reminderCards);
        scheduler.tick();
        assertEquals(1, sender.reminderCards);

        clock.now = Instant.parse("2026-10-22T06:20:00Z");
        scheduler.tick();
        assertEquals(2, sender.reminderCards);

        service.recover(incidentId, "ou_owner");
        clock.now = Instant.parse("2026-10-22T07:00:00Z");
        scheduler.tick();
        assertEquals(2, sender.reminderCards);
    }

    @Test
    void acknowledgementStopsReminderSchedule() {
        var receipt = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:2", "2026-10-22T06:00:00Z"));
        var incident = repository.get(receipt.path("incidentId").asText());

        service.ack(incident.path("incidentId").asText(), "ou_owner", incident.path("version").asInt());
        clock.now = Instant.parse("2026-10-22T06:30:00Z");
        scheduler.tick();

        assertEquals("ACKED", repository.get(incident.path("incidentId").asText()).path("status").asText());
        assertEquals(0, sender.reminderCards);
    }

    private void configure(String appId) {
        var body = mapper.createObjectNode().put("expectedVersion", 0)
                .put("alertsEnabled", true).put("businessName", "内容分发")
                .put("fixedChatId", "oc_fixed").put("testChatId", "oc_test")
                .put("p1AutoCreateGroup", true)
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
