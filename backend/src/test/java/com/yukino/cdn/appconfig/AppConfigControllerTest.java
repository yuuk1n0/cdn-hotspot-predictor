package com.yukino.cdn.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.operations.DefaultOperationsService;
import com.yukino.cdn.operations.OperationsRepository;
import com.yukino.cdn.prediction.PredictionService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AppConfigControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private DriverManagerDataSource data;

    @BeforeEach void setup() {
        data = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var predictions = new PredictionService(null, mapper, Clock.systemUTC(), null) {
            @Override public List<com.fasterxml.jackson.databind.node.ObjectNode> published(String app, String runId, LocalDate date, int horizon) {
                return List.of();
            }
        };
        var service = new DefaultOperationsService(new OperationsRepository(new NamedParameterJdbcTemplate(data), mapper),
                predictions, (app, horizon) -> {
                    var evidence = mapper.createObjectNode().put("appId", app).put("horizonDays", horizon);
                    evidence.putArray("availableModels").addObject().put("modelVersion", "hotspot-simple-v1").put("predictionSource", "MODEL");
                    return evidence;
                }, Clock.systemUTC(), mapper);
        mvc = MockMvcBuilders.standaloneSetup(new AppConfigController(service)).setControllerAdvice(new AppConfigApiAdvice()).build();
    }

    @Test void readsAndUpdatesAppScopedConfigWithoutChangingModels() throws Exception {
        mvc.perform(get("/api/v1/app-configs/10350")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.notificationsEnabled").value(false));
        mvc.perform(put("/api/v1/app-configs/10350").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"handlingHours\":48,\"notificationsEnabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/v1/app-configs/10350")).andExpect(jsonPath("$.handlingHours").value(48));
        mvc.perform(get("/api/v1/app-configs/10066")).andExpect(jsonPath("$.version").value(0));
        mvc.perform(put("/api/v1/app-configs/10350").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"handlingHours\":40}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(put("/api/v1/app-configs/10350").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"dailyModelVersion\":\"other\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("INVALID_CONFIG"));
    }

    @Test void suggestionPersistsEvidenceButCannotSwitchAnUnqualifiedModel() throws Exception {
        String json = mvc.perform(post("/api/v1/app-configs/10350/model-suggestions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"horizonDays\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canSwitch").value(false))
                .andExpect(jsonPath("$.reasonCodes").isArray()).andReturn().getResponse().getContentAsString();
        String suggestionId = mapper.readTree(json).path("suggestionId").asText();
        mvc.perform(get("/api/v1/app-configs/10350")).andExpect(jsonPath("$.latestSuggestion.suggestionId").value(suggestionId));
        int version = mapper.readTree(mvc.perform(get("/api/v1/app-configs/10350"))
                .andReturn().getResponse().getContentAsString()).path("version").asInt();
        mvc.perform(post("/api/v1/app-configs/10350/model-switches").contentType(MediaType.APPLICATION_JSON)
                .content("{\"suggestionId\":\"" + suggestionId + "\",\"expectedVersion\":" + version + ",\"confirmed\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MODEL_SWITCH_NOT_AVAILABLE"));
        mvc.perform(get("/api/v1/app-configs/10350")).andExpect(jsonPath("$.version").value(version));
    }

    @Test void rejectsMalformedRequestsAndMissingStorageWithoutClaimingSuccess() throws Exception {
        mvc.perform(post("/api/v1/app-configs/10350/model-suggestions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"horizonDays\":\"1\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/app-configs/10350/model-switches").contentType(MediaType.APPLICATION_JSON)
                .content("{\"suggestionId\":\"x\",\"expectedVersion\":0,\"confirmed\":false}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/app-configs/10350").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnprocessableEntity());
        new org.springframework.jdbc.core.JdbcTemplate(data).execute("DROP TABLE app_config");
        mvc.perform(get("/api/v1/app-configs/10350")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("APP_CONFIG_STORAGE_UNAVAILABLE"));
    }
}
