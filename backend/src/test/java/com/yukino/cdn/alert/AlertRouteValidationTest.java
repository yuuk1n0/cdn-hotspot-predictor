package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRouteValidationTest {
    @Test
    void routeTestUsesOnlyTheConfiguredTestChat() {
        var mapper = new ObjectMapper();
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);
        var routes = new AlertRouteService(new OperationsRepository(
                new NamedParameterJdbcTemplate(data), mapper), mapper, clock);
        configure(routes, mapper, "10350", "oc_test_10350");
        configure(routes, mapper, "10066", "");
        var sender = new FakeAlertSender();
        var action = new AlertRouteActionService(routes, sender);

        action.test("10350");
        assertEquals("oc_test_10350", sender.lastChatId);
        assertTrue(sender.lastCard.contains("DEMO"));
        assertEquals("TEST_CHAT_REQUIRED",
                assertThrows(PredictionProblem.class, () -> action.test("10066")).code());
        assertEquals(1, sender.sentCards);
    }

    private static void configure(AlertRouteService routes, ObjectMapper mapper, String appId, String testChatId) {
        var body = mapper.createObjectNode().put("expectedVersion", 0)
                .put("alertsEnabled", true).put("businessName", "业务 " + appId)
                .put("fixedChatId", "oc_fixed_" + appId).put("testChatId", testChatId)
                .put("p1AutoCreateGroup", true)
                .put("ackDeadlineMinutes", 5).put("remindIntervalMinutes", 15)
                .put("maxReminderCount", 4).put("activeWindowMinutes", 60);
        body.putArray("ownerOpenIds").add("ou_owner");
        body.putArray("backupOpenIds").add("ou_backup");
        routes.update(appId, body);
    }
}
