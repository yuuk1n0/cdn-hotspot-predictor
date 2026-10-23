package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsRepository;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlertRouteServiceTest {
    @Test
    void newAppDefaultsToAlertsOffAndP1AutoGroupOn() {
        var service = service(new ObjectMapper());

        var route = service.get("10350");

        assertFalse(route.path("alertsEnabled").asBoolean());
        assertTrue(route.path("p1AutoCreateGroup").asBoolean());
    }

    @Test
    void updatePersistsAlertRouteAndIncrementsVersion() {
        var mapper = new ObjectMapper();
        var service = service(mapper);

        var saved = service.update("10350", routeBody(mapper));

        assertEquals(1, saved.path("version").asInt());
        assertEquals("oc_10350", saved.path("fixedChatId").asText());
        assertEquals("oc_test_10350", saved.path("testChatId").asText());
        assertEquals("ou_owner", service.get("10350").path("ownerOpenIds").get(0).asText());
    }

    @Test
    void enabledRouteRequiresOwnerAndFixedChat() {
        var mapper = new ObjectMapper();
        var service = service(mapper);
        var body = routeBody(mapper).put("fixedChatId", "");
        body.putArray("ownerOpenIds");

        var error = assertThrows(PredictionProblem.class, () -> service.update("10350", body));

        assertEquals("INVALID_CONFIG", error.code());
    }

    @Test
    void reminderTimingMustStayWithinApprovedBounds() {
        var mapper = new ObjectMapper();
        var service = service(mapper);
        for (String field : java.util.List.of(
                "ackDeadlineMinutes", "remindIntervalMinutes", "maxReminderCount", "activeWindowMinutes")) {
            var body = routeBody(mapper).put(field, 0);
            var error = assertThrows(PredictionProblem.class, () -> service.update("10350", body));
            assertEquals("INVALID_CONFIG", error.code());
        }
    }

    @Test
    void enabledP1RouteRequiresBackupOwner() {
        var mapper = new ObjectMapper();
        var service = service(mapper);
        var body = routeBody(mapper);
        body.putArray("backupOpenIds");

        var error = assertThrows(PredictionProblem.class, () -> service.update("10350", body));

        assertEquals("INVALID_CONFIG", error.code());
    }

    @Test
    void alertRouteUpdateCannotChangeModelFields() {
        var mapper = new ObjectMapper();
        var service = service(mapper);
        var body = routeBody(mapper).put("dailyModelVersion", "not-allowed");

        var error = assertThrows(PredictionProblem.class, () -> service.update("10350", body));

        assertEquals("INVALID_CONFIG", error.code());
    }

    @Test
    void malformedRouteTypesAndIdentifiersCannotBePersisted() {
        var mapper = new ObjectMapper();
        var service = service(mapper);
        var wrongBoolean = routeBody(mapper).put("alertsEnabled", "true");
        assertEquals("INVALID_CONFIG",
                assertThrows(PredictionProblem.class, () -> service.update("10350", wrongBoolean)).code());

        var wrongOwners = routeBody(mapper).put("ownerOpenIds", "ou_owner");
        assertEquals("INVALID_CONFIG",
                assertThrows(PredictionProblem.class, () -> service.update("10350", wrongOwners)).code());

        var wrongChat = routeBody(mapper).put("fixedChatId", "https://open.feishu.cn/not-a-chat-id");
        assertEquals("INVALID_CONFIG",
                assertThrows(PredictionProblem.class, () -> service.update("10350", wrongChat)).code());
    }

    @Test
    void existingAppConfigGainsAlertDefaultsWithoutLosingRoutineSettings() {
        var mapper = new ObjectMapper();
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var repository = new OperationsRepository(new NamedParameterJdbcTemplate(data), mapper);
        repository.saveConfig("10350", mapper.createObjectNode()
                .put("notificationsEnabled", true)
                .put("handlingHours", 24), 0, Instant.parse("2026-10-22T00:00:00Z"));
        var service = new AlertRouteService(repository, mapper,
                Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));

        var route = service.get("10350");

        assertTrue(route.path("notificationsEnabled").asBoolean());
        assertTrue(route.has("p1AutoCreateGroup"));
        assertFalse(route.path("alertsEnabled").asBoolean());
    }

    @Test
    void staleVersionIsRejectedAndDifferentAppsStayIsolated() {
        var mapper = new ObjectMapper();
        var service = service(mapper);
        var body = routeBody(mapper);
        service.update("10350", body);

        assertEquals("VERSION_CONFLICT",
                assertThrows(PredictionProblem.class, () -> service.update("10350", body)).code());
        assertFalse(service.get("10066").path("alertsEnabled").asBoolean());
        assertEquals("", service.get("10066").path("fixedChatId").asText());
    }

    private AlertRouteService service(ObjectMapper mapper) {
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        return new AlertRouteService(
                new OperationsRepository(new NamedParameterJdbcTemplate(data), mapper), mapper,
                Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));
    }

    private ObjectNode routeBody(ObjectMapper mapper) {
        var body = mapper.createObjectNode().put("expectedVersion", 0)
                .put("alertsEnabled", true).put("businessName", "内容分发")
                .put("fixedChatId", "oc_10350").put("testChatId", "oc_test_10350")
                .put("p1AutoCreateGroup", true)
                .put("ackDeadlineMinutes", 5).put("remindIntervalMinutes", 15)
                .put("maxReminderCount", 4).put("activeWindowMinutes", 60);
        body.putArray("ownerOpenIds").add("ou_owner");
        body.putArray("backupOpenIds").add("ou_backup");
        return body;
    }
}
