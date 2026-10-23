package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PredictionControllerTest {
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final String REQUEST = """
            {"datasetId":"ds_one","appId":"10350","cutoffDate":"2026-10-06","horizonDays":7,"modelVersion":"contract-demo-v1"}
            """;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("prediction-test-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        var settings = new PredictionSettings("http://python.test", Duration.ofSeconds(3), Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(180), Duration.ofSeconds(10));
        var service = new PredictionService(new PredictionRepository(new NamedParameterJdbcTemplate(source)), mapper, Clock.systemUTC(), settings);
        mvc = MockMvcBuilders.standaloneSetup(new PredictionController(service)).setControllerAdvice(new PredictionApiAdvice()).build();
    }

    @Test
    void returns202AndQueriesTheDurableBusinessTask() throws Exception {
        String body = mvc.perform(post("/api/v1/predictions").header("Idempotency-Key", "one")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).path("taskId").textValue();
        mvc.perform(get("/api/v1/tasks/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(id)).andExpect(jsonPath("$.datasetId").value("ds_one"))
                .andExpect(jsonPath("$.runId").isEmpty()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/v1/predictions").header("Idempotency-Key", "one").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.taskId").value(id));
        mvc.perform(post("/api/v1/predictions").header("Idempotency-Key", "one").contentType(MediaType.APPLICATION_JSON).content(REQUEST.replace("\"horizonDays\":7", "\"horizonDays\":1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void rejectsBadJsonMissingHeaderNumericIdsAndUnsupportedHorizonWithoutPersisting() throws Exception {
        for (String body : List.of("not-json", "{}", "[]", REQUEST.replace("\"ds_one\"", "123"),
                REQUEST.replace("\"horizonDays\":7", "\"horizonDays\":1.5"),
                REQUEST.replace("\"horizonDays\":7", "\"horizonDays\":2"), REQUEST.replace("2026-10-06", "2026-02-30"))) {
            mvc.perform(post("/api/v1/predictions").header("Idempotency-Key", "one").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/api/v1/predictions").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM prediction_task", Long.class));
    }

    @Test
    void missingTaskAndMissingTableDoNotReturnFakeSuccess() throws Exception {
        mvc.perform(get("/api/v1/tasks/unknown")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/predictions/unknown")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
        // Only the isolated test database is affected, never the real MySQL instance.
        jdbc.execute("DROP TABLE prediction_run");
        jdbc.execute("DROP TABLE prediction_task");
        mvc.perform(post("/api/v1/predictions").header("Idempotency-Key", "one").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("TASK_STORAGE_UNAVAILABLE"));
    }
}
