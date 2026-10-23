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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var clock = Clock.fixed(Instant.parse("2026-10-22T06:04:00Z"), ZoneOffset.UTC);
        var jdbc = new NamedParameterJdbcTemplate(data);
        var routes = new AlertRouteService(new OperationsRepository(jdbc, mapper), mapper, clock);
        var service = new AlertIncidentService(new AlertIncidentRepository(jdbc, mapper), routes, mapper, clock);
        var settings = new AlertSettings("", "", "", "ingest-token", "https://open.feishu.cn", false);
        mvc = MockMvcBuilders.standaloneSetup(new AlertController(service, settings))
                .setControllerAdvice(new AlertApiAdvice()).build();
    }

    @Test
    void ingestReturnsAcceptedAndDoesNotClaimFeishuDelivery() throws Exception {
        var response = mvc.perform(post("/api/v1/alerts/events")
                        .header("X-Alert-Token", "ingest-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AlertTestFixtures.alert("evt-1", "ALERT", "10350",
                                "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z").toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentId").isString())
                .andExpect(jsonPath("$.routingStatus").value("ROUTING_FAILED"))
                .andReturn().getResponse().getContentAsString();
        String incidentId = mapper.readTree(response).path("incidentId").asText();

        mvc.perform(get("/api/v1/alerts/{incidentId}", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentId").value(incidentId))
                .andExpect(jsonPath("$.status").value("OPEN"));

        mvc.perform(get("/api/v1/alerts")
                        .param("appId", "10350")
                        .param("status", "OPEN")
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].incidentId").value(incidentId));
    }

    @Test
    void ingestRejectsWrongTokenBeforeCreatingIncident() throws Exception {
        mvc.perform(post("/api/v1/alerts/events")
                        .header("X-Alert-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AlertTestFixtures.alert("evt-1", "ALERT", "10350",
                                "IMPORT_FAILED", "task:1", "2026-10-22T06:03:12Z").toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ALERT_INGEST_UNAUTHORIZED"));

        mvc.perform(get("/api/v1/alerts").param("appId", "10350"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }
}
