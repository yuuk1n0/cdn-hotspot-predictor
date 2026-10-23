package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrafficMetricsServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private TrafficMetricsRepository repository;
    private ObjectNode snapshot;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE imported_dataset (dataset_id VARCHAR(128) PRIMARY KEY,app_id VARCHAR(32),dataset_type VARCHAR(32),dataset_source VARCHAR(16),status VARCHAR(16),created_at_ms BIGINT)");
        jdbc.execute("CREATE TABLE file_daily_observation (dataset_id VARCHAR(128),app_id VARCHAR(32),observed_date DATE,file_key VARCHAR(512),file_key_hash CHAR(64),traffic_bytes DECIMAL(65,0))");
        jdbc.execute("CREATE TABLE app_daily_total (dataset_id VARCHAR(128),app_id VARCHAR(32),observed_date DATE,total_traffic_bytes DECIMAL(65,9),source_unit VARCHAR(16))");
        repository = new TrafficMetricsRepository(new NamedParameterJdbcTemplate(source));
        snapshot = (ObjectNode) mapper.createObjectNode()
                .put("runId", "run_one").put("appId", "10066").put("datasetId", "ds_file")
                .put("datasetSource", "REAL").put("predictionSource", "MODEL").put("horizonDays", 1)
                .put("predictionStartDate", "2026-04-16").put("predictionEndDate", "2026-04-16");
        var items = snapshot.putArray("items");
        items.addObject().put("fileKey", "a").put("predictedBytes", "60");
        items.addObject().put("fileKey", "b").put("predictedBytes", "40");
        jdbc.update("INSERT INTO imported_dataset VALUES('ds_file','10066','FILE_DAILY','REAL','READY',1)");
        jdbc.update("INSERT INTO file_daily_observation VALUES('ds_file','10066','2026-04-16','a',?,60)", TrafficMetricsService.hash("a"));
        jdbc.update("INSERT INTO file_daily_observation VALUES('ds_file','10066','2026-04-16','b',?,40)", TrafficMetricsService.hash("b"));
    }

    @Test
    void resolvesTheLatestReadyAppTotalDatasetInsteadOfJoiningTheFileDataset() {
        jdbc.update("INSERT INTO imported_dataset VALUES('ds_total','10066','APP_DAILY_TOTAL','REAL','READY',2)");
        jdbc.update("INSERT INTO app_daily_total VALUES('ds_total','10066','2026-04-16',400,'GB_DECIMAL')");

        JsonNode result = service().forRun("run_one");

        assertEquals("ds_total", result.path("appTotalDatasetId").asText());
        assertEquals("GB_DECIMAL", result.path("appTotalUnit").asText());
        var predicted = result.path("actualPredictedTop50");
        assertEquals(0, predicted.path("value").decimalValue().compareTo(new BigDecimal("25")));
        assertEquals("100", predicted.path("numeratorBytes").asText());
        assertEquals(0, new BigDecimal(predicted.path("denominatorBytes").asText()).compareTo(new BigDecimal("400")));
        assertNull(predicted.path("reasonCode").textValue());
    }

    @Test
    void refusesToUseAnUnconfirmedUnitAsADenominator() {
        jdbc.update("INSERT INTO imported_dataset VALUES('ds_total','10066','APP_DAILY_TOTAL','REAL','READY',2)");
        jdbc.update("INSERT INTO app_daily_total VALUES('ds_total','10066','2026-04-16',400,'GIB_RAW')");

        JsonNode result = service().forRun("run_one");

        assertEquals("APP_TOTAL_UNIT_UNCONFIRMED", result.path("actualPredictedTop50").path("reasonCode").asText());
        assertEquals("APP_TOTAL_UNIT_UNCONFIRMED", result.path("actualTop50").path("reasonCode").asText());
        assertNull(result.path("actualPredictedTop50").path("value").textValue());
    }

    @Test
    void reportsMissingAppTotalWithoutInventingAZeroDenominator() {
        JsonNode result = service().forRun("run_one");

        assertEquals("NO_CONFIRMED_APP_TOTAL", result.path("actualPredictedTop50").path("reasonCode").asText());
        assertNull(result.path("appTotalDatasetId").textValue());
        assertNull(result.path("actualPredictedTop50").path("denominatorBytes").textValue());
    }

    private TrafficMetricsService service() {
        var predictions = new PredictionService(null, mapper, clock, null) {
            @Override
            public JsonNode result(String runId) {
                return snapshot.deepCopy();
            }
        };
        return new TrafficMetricsService(predictions, repository, mapper, clock);
    }
}
