package com.yukino.cdn.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.prediction.PredictionService;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationsServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
    final List<ObjectNode> runs = new ArrayList<>();
    boolean recommendSwitch;
    JdbcTemplate jdbc;
    DefaultOperationsService service;
    @BeforeEach void setup() throws Exception {
        var data = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        jdbc = new JdbcTemplate(data);
        var store = new OperationsRepository(new NamedParameterJdbcTemplate(data), mapper);
        var predictions = new PredictionService(null, mapper, clock, null) {
            @Override public List<ObjectNode> published(String app, String runId, LocalDate date, int horizon) {
                return runs.stream().filter(r -> r.path("appId").asText().equals(app))
                        .filter(r -> runId == null || r.path("runId").asText().equals(runId))
                        .filter(r -> date == null || r.path("predictionStartDate").asText().equals(date.toString()))
                        .filter(r -> horizon == 0 || r.path("horizonDays").asInt() == horizon).map(ObjectNode::deepCopy).toList();
            }
        };
        service = new DefaultOperationsService(store, predictions, new ModelEvidenceGateway() {
            public ObjectNode metrics(String app, int horizon) {
                var result = mapper.createObjectNode().put("appId", app).put("horizonDays", horizon).put("formalSelectionEligible", false);
                result.putArray("availableModels").addObject().put("modelVersion", "hotspot-simple-v1").put("predictionSource", "MODEL");
                result.putArray("reasonCodes").add("SINGLE_DEPLOYED_MODEL").add("UNCONFIRMED_LABELS"); return result;
            }
            public ObjectNode review(String app, int horizon) {
                var result = mapper.createObjectNode().put("appId", app).put("horizonDays", horizon)
                        .put("modelVersion", "hotspot-simple-v1").put("metricBasis", "REAL_OBSERVED_FUTURE_ROLLING_WINDOW")
                        .put("currentMethod", "last_day").put("cutoffStartDate", "2026-09-01").put("cutoffEndDate", "2026-10-10")
                        .put("cutoffCount", 5).put("canSwitch", recommendSwitch).put("targetModelVersion", "hotspot-simple-v1")
                        .put("targetMethod", recommendSwitch ? "mean_7" : "last_day")
                        .put("improvementRecall", recommendSwitch ? 0.03 : 0.0).put("improvementNdcg", 0.0);
                result.putObject("currentSpec").put("method", "last_day").put("window", 1).putNull("halfLife");
                result.putObject("targetSpec").put("method", recommendSwitch ? "mean" : "last_day").put("window", recommendSwitch ? 7 : 1).putNull("halfLife");
                result.putObject("currentMetrics").put("recallAt50", 0.6).put("ndcgAt50", 0.8);
                result.putArray("candidates").addObject().put("method", "last_day").putObject("mean").put("recallAt50", 0.6).put("ndcgAt50", 0.8);
                result.putArray("reasonCodes").add(recommendSwitch ? "PARAMETER_REPLACEMENT_RECOMMENDED" : "CURRENT_CONFIG_NONINFERIOR");
                result.putArray("recommendations").add("fixture");
                return result;
            }
        }, clock, mapper);
        var run = (ObjectNode) mapper.readTree("""
                {"runId":"run_old","appId":"10066","datasetId":"ds_one","datasetSource":"REAL","predictionSource":"MODEL",
                "modelVersion":"hotspot-simple-v1","horizonDays":1,"predictionStartDate":"2026-10-20","predictionEndDate":"2026-10-20",
                "publishedAt":"2026-10-19T23:59:59Z","items":[{"rank":1,"fileKey":"a","predictedBytes":"9007199254740993"},{"rank":2,"fileKey":"b","predictedBytes":"2"}]}
                """);
        runs.add(run);
    }
    @Test void todayNeverSilentlyUsesOldRunAndCrossAppIsRejected() {
        assertEquals("NO_CURRENT_RUN", assertThrows(PredictionProblem.class, () -> service.run("10066", null, LocalDate.of(2026,10,21), 1)).code());
        assertThrows(PredictionProblem.class, () -> service.run("other", "run_old", null, 1));
        assertThrows(PredictionProblem.class, () -> service.run("10066", "run_old", LocalDate.of(2026,10,21), 1));
    }
    @Test void completionExclusionAndOverdueUseOnlyTheSelectedEvidenceScope() throws Exception {
        var record = mapper.createObjectNode().put("id", "cmp_a").put("appId", "10066").put("fileKey", "a").put("evidenceSource", "REAL").put("status", "COMPRESSED").put("version", 1);
        jdbc.update("INSERT INTO compression_record VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", "cmp_a", "10066", "a", OperationsSupport.hash("a"), "REAL", "COMPRESSED", record.toString(), 1, "id_a", "req_a", 0, 0);
        var pending = service.pending("10066", "run_old", "REAL");
        assertEquals(1, pending.path("items").size());
        assertEquals("b", pending.path("items").get(0).path("fileKey").asText());
        assertEquals(2, service.pending("10066", "run_old", "TEST").path("items").size());
        assertEquals(1, service.overdue("10066", "run_old", "REAL").path("items").size());
        runs.get(0).put("publishedAt", "2026-10-20T00:00:00Z");
        assertEquals(0, service.overdue("10066", "run_old", "REAL").path("items").size());
    }
    @Test void latestRunDoesNotSilentlySelectMockResults() throws Exception {
        var mock = (ObjectNode) mapper.readTree("""
                {"runId":"run_mock","appId":"10066","datasetId":"ds_mock","datasetSource":"REAL","predictionSource":"MOCK",
                 "modelVersion":"hotspot-simple-v1","horizonDays":1,"predictionStartDate":"2026-10-21","predictionEndDate":"2026-10-21",
                 "items":[]}
                """);
        runs.add(0, mock);

        assertEquals("run_old", service.latestRun("10066", null, 1).path("runId").asText());
        assertEquals(1, service.runList("10066", null, 1).path("items").size());
    }
    @Test void configIsPersistedWithOptimisticVersionAndNoModelWriteShortcut() {
        assertFalse(service.config("10066").path("notificationsEnabled").asBoolean());
        var body = mapper.createObjectNode().put("expectedVersion", 0).put("notificationsEnabled", false).put("handlingHours", 48);
        assertEquals(1, service.updateConfig("10066", body).path("version").asInt());
        assertEquals(48, service.config("10066").path("handlingHours").asInt());
        assertThrows(PredictionProblem.class, () -> service.updateConfig("10066", body));
        assertThrows(PredictionProblem.class, () -> service.updateConfig("10066", mapper.createObjectNode().put("expectedVersion", 1).put("dailyModelVersion", "arbitrary")));
        assertFalse(service.suggestion("10066", 1, null).path("canSwitch").asBoolean());
    }

    @Test void confirmedReviewCanReplaceOnlyTheAppAndHorizonParameters() {
        recommendSwitch = true;
        var suggestion = service.suggestion("10066", 1, null);
        assertTrue(suggestion.path("canSwitch").asBoolean());
        assertEquals("mean", suggestion.path("targetSpec").path("method").asText());
        var version = service.config("10066").path("version").asInt();
        var switched = service.switchModel("10066", mapper.createObjectNode().put("confirmed", true)
                .put("expectedVersion", version).put("suggestionId", suggestion.path("suggestionId").asText()));
        assertEquals(7, switched.path("dailyModelSpec").path("window").asInt());
        assertTrue(switched.path("weeklyModelSpec").isMissingNode() || switched.path("weeklyModelSpec").isNull());
        assertEquals(1, switched.path("modelSwitchHistory").size());
    }
    @Test void toolsBindContextAndReportMissingFactsRatherThanMoneyZero() {
        var context = mapper.createObjectNode().put("appId", "10066").put("businessDate", "2026-10-20").put("horizonDays", 1).put("runId", "run_old");
        assertThrows(PredictionProblem.class, () -> service.evidence("getTopHotspots", context, mapper.createObjectNode().put("appId", "other")));
        var result = service.evidence("getCompressionEffects", context, mapper.createObjectNode());
        assertTrue(result.toString().contains("NO_COMPRESSION_OBSERVATION"));
        assertFalse(result.toString().contains("\"estimatedNetBenefitYuan\":\"0"));
        assertThrows(PredictionProblem.class, () -> service.evidence("executeSql", context, mapper.createObjectNode()));
    }
}
