package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertDispatcherTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:05:00Z"), ZoneOffset.UTC);
    private AlertIncidentRepository repository;
    private AlertRouteService routes;
    private FakeAlertSender sender;
    private AlertDispatcher dispatcher;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var jdbc = new NamedParameterJdbcTemplate(data);
        this.jdbc = new JdbcTemplate(data);
        var operations = new OperationsRepository(jdbc, mapper);
        routes = new AlertRouteService(operations, mapper, clock);
        repository = new AlertIncidentRepository(jdbc, mapper);
        sender = new FakeAlertSender();
        dispatcher = new AlertDispatcher(repository, new AlertDeliveryRepository(jdbc, mapper),
                routes, sender, new AlertCardRenderer(mapper));
    }

    @Test
    void p1CreatesOneIncidentGroupWhileP2UsesFixedGroup() {
        configure("10350", "oc_fixed_10350", true);
        configure("10066", "oc_fixed_10066", false);
        var p1 = incident("10350", "P1");
        dispatcher.open(p1, routes.get("10350"));
        dispatcher.update(repository.get(p1.path("incidentId").asText()));
        assertEquals(1, sender.createdChats);
        assertEquals(List.of("ou_owner", "ou_backup"), sender.invited);
        assertEquals("oc_created", sender.lastChatId);
        assertTrue(sender.lastCard.contains(p1.path("incidentId").asText()));
        assertTrue(sender.lastCard.contains("\"schema\":\"2.0\""));
        assertTrue(sender.lastCard.contains("<at id=ou_owner></at>"));
        assertTrue(sender.lastCard.contains("3 次"));
        assertTrue(sender.lastCard.contains("task:"));
        assertTrue(sender.lastCard.contains("**事故号**\\n"));
        assertTrue(sender.lastCard.contains("\"action\":\"ACK\""));
        assertTrue(sender.lastCard.contains("\"action\":\"PROCESSING\""));
        assertTrue(sender.lastCard.contains("\"action\":\"RECOVER\""));

        var p2 = incident("10066", "P2");
        dispatcher.open(p2, routes.get("10066"));
        assertEquals(1, sender.createdChats);
        assertEquals("oc_fixed_10066", sender.lastChatId);
    }

    @Test
    void p1FallsBackToFixedGroupWithoutCreatingASecondGroupLater() {
        configure("10350", "oc_fixed_10350", true);
        sender.createFails = true;
        var incident = incident("10350", "P1");

        dispatcher.open(incident, routes.get("10350"));
        assertEquals("oc_fixed_10350", sender.lastChatId);
        assertEquals("GROUP_CREATE_FAILED", repository.get(
                incident.path("incidentId").asText()).path("routingError").asText());

        dispatcher.update(repository.get(incident.path("incidentId").asText()));
        assertEquals(1, sender.createAttempts);
    }

    @Test
    void recoveryCardUsesTheSameIncidentChat() {
        configure("10350", "oc_fixed_10350", true);
        var incident = incident("10350", "P1");
        dispatcher.open(incident, routes.get("10350"));
        var routed = repository.get(incident.path("incidentId").asText());
        String chatId = routed.path("chatId").asText();
        routed.put("status", "RECOVERED");

        dispatcher.recover(routed);

        assertEquals(chatId, sender.lastChatId);
        assertTrue(sender.lastCard.contains("RECOVERED"));
    }

    @Test
    void enabledIncidentIsDispatchedDuringIngest() {
        configure("10350", "oc_fixed_10350", true);
        var service = new AlertIncidentService(repository, routes, mapper, clock, dispatcher);

        var receipt = service.ingest(AlertTestFixtures.alert(
                "evt-auto", "ALERT", "10350", "IMPORT_FAILED", "task:auto", "2026-10-22T06:03:12Z"));

        assertEquals("ROUTED", receipt.path("routingStatus").asText());
        assertEquals(1, sender.sentCards);
    }

    @Test
    void deliveryAuditRecordsGroupInviteAndCardPhases() {
        configure("10350", "oc_fixed_10350", true);
        var incident = incident("10350", "P1");

        dispatcher.open(incident, routes.get("10350"));

        assertEquals(List.of("GROUP_CREATE", "MEMBER_INVITE", "OPEN"),
                jdbc.queryForList("SELECT alert_phase FROM notification_delivery " +
                        "WHERE incident_id=? ORDER BY created_at_ms,alert_phase", String.class,
                        incident.path("incidentId").asText()));
    }

    @Test
    void unconfirmedCardDeliveryIsRecordedAsUnknownAndRoutesFail() {
        configure("10350", "oc_fixed_10350", true);
        sender.sendFails = true;
        var incident = incident("10350", "P1");

        dispatcher.open(incident, routes.get("10350"));

        var stored = repository.get(incident.path("incidentId").asText());
        assertEquals("ROUTING_FAILED", stored.path("routingStatus").asText());
        assertEquals("FEISHU_TIMEOUT", stored.path("routingError").asText());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM notification_delivery " +
                "WHERE incident_id=? AND alert_phase='OPEN'", String.class,
                incident.path("incidentId").asText()));
    }

    private ObjectNode incident(String appId, String severity) {
        var service = new AlertIncidentService(repository, routes, mapper, clock);
        var receipt = service.ingest(AlertTestFixtures.alert("evt-" + UUID.randomUUID(), "ALERT",
                appId, "IMPORT_FAILED", "task:" + UUID.randomUUID(), "2026-10-22T06:03:12Z")
                .put("severity", severity));
        return repository.get(receipt.path("incidentId").asText());
    }

    private void configure(String appId, String fixedChatId, boolean autoCreate) {
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
}
