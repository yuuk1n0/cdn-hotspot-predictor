package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.operations.OperationsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertRouteControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private FakeAlertSender sender;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var service = new AlertRouteService(
                new OperationsRepository(new NamedParameterJdbcTemplate(data), mapper), mapper,
                Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));
        sender = new FakeAlertSender();
        mvc = MockMvcBuilders.standaloneSetup(new AlertRouteController(
                        service, new AlertRouteActionService(service, sender)))
                .setControllerAdvice(new AlertRouteApiAdvice()).build();
    }

    @Test
    void readsUpdatesAndListsAlertRoutesByApp() throws Exception {
        mvc.perform(get("/api/v1/alert-routes/10350"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.alertsEnabled").value(false));

        mvc.perform(put("/api/v1/alert-routes/10350")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.fixedChatId").value("oc_10350"));

        mvc.perform(get("/api/v1/alert-routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].appId").value("10350"))
                .andExpect(jsonPath("$[0].alertsEnabled").value(true));
    }

    @Test
    void mapsInvalidRouteToStructured422() throws Exception {
        mvc.perform(put("/api/v1/alert-routes/10350")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody().put("dailyModelVersion", "blocked").toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_CONFIG"));
    }

    @Test
    void validatesConfiguredChatsAndSendsTestCardOnlyToTestChat() throws Exception {
        mvc.perform(put("/api/v1/alert-routes/10350")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody().toString()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/alert-routes/10350/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v1/alert-routes/10350/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));

        assertEquals("oc_test_10350", sender.lastChatId);
        assertEquals(1, sender.sentCards);
        assertEquals(3, sender.validateChatCalls);
        assertEquals(2, sender.validatedUsers);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode routeBody() {
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
