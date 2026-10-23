package com.yukino.cdn.compression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.DefaultOperationsService;
import com.yukino.cdn.operations.OperationsRepository;
import com.yukino.cdn.prediction.PredictionProblem;
import com.yukino.cdn.prediction.PredictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CompressionRegistrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private final List<ObjectNode> runs = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        var data = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        jdbc = new JdbcTemplate(data);
        var store = new OperationsRepository(new NamedParameterJdbcTemplate(data), mapper);
        var predictions = new PredictionService(null, mapper, clock, null) {
            @Override public List<ObjectNode> published(String app, String runId, LocalDate date, int horizon) {
                return runs.stream().filter(r -> app.equals(r.path("appId").asText()) && (runId == null || runId.equals(r.path("runId").asText())))
                        .map(ObjectNode::deepCopy).toList();
            }
        };
        runs.add((ObjectNode) mapper.readTree("""
                {"runId":"run_real","appId":"10350","datasetSource":"REAL","predictionSource":"MODEL","items":[{"fileKey":"a.mp4"}]}
                """));
        runs.add((ObjectNode) mapper.readTree("""
                {"runId":"run_fake","appId":"10066","datasetSource":"FAKE","predictionSource":"MOCK","items":[{"fileKey":"a.mp4"}]}
                """));
        var operations = new DefaultOperationsService(store, predictions, (app,horizon) -> mapper.createObjectNode(), clock, mapper);
        var service = new CompressionRegistrationService(store, operations, mapper, clock);
        mvc = MockMvcBuilders.standaloneSetup(new CompressionController(service)).setControllerAdvice(new CompressionApiAdvice()).build();
    }

    private String completed(String run, String app, String source) {
        return """
                {"runId":"%s","appId":"%s","fileKey":"a.mp4","evidenceSource":"%s","status":"COMPRESSED",
                "originalSizeBytes":"100000000","originalSizeSource":"%s","originalSizeEvidence":"人工测量报告 A",
                "compressedFileKey":"a-small.mp4","compressedSizeBytes":"60000000",
                "quality":{"metric":"VMAF","value":92,"evidenceNote":"测试报告 A"},
                "handler":"operator","remark":"登记样本","compressedAt":"2026-10-06T02:00:00Z"}
                """.formatted(run, app, source, source.equals("REAL") ? "UNSUPPORTED" : "MANUAL_TEST");
    }

    private JsonNode create(String key, String body) throws Exception {
        String response = mvc.perform(post("/api/v1/compressions").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    @Test void testEvidencePersistsCalculatesAndReusesIdempotentReceipt() throws Exception {
        var body = completed("run_fake", "10066", "TEST");
        var row = create("one", body);
        assertEquals("0.4", row.path("compressionRatio").decimalValue().stripTrailingZeros().toPlainString());
        assertTrue(row.path("qualityPassed").asBoolean());
        assertEquals("PENDING_OBSERVATION", row.path("effectiveCompressionStatus").asText());
        mvc.perform(post("/api/v1/compressions").header("Idempotency-Key", "one").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(row.path("id").asText()));
        mvc.perform(get("/api/v1/compressions").param("appId", "10066").param("fileKey", "a.mp4").param("evidenceSource", "TEST"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(row.path("id").asText()));
        mvc.perform(get("/api/v1/compressions").param("appId", "10350")).andExpect(jsonPath("$.total").value(0));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM compression_record", Long.class));
    }

    @Test void realEvidenceIsRejectedAndManualEvidenceRemainsAvailable() throws Exception {
        mvc.perform(post("/api/v1/compressions").header("Idempotency-Key", "real")
                .contentType(MediaType.APPLICATION_JSON).content(completed("run_real", "10350", "REAL")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_SOURCE_UNSUPPORTED"));
        mvc.perform(post("/api/v1/compressions").header("Idempotency-Key", "fake-real")
                .contentType(MediaType.APPLICATION_JSON).content(completed("run_fake", "10066", "REAL")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_SOURCE_UNSUPPORTED"));
        mvc.perform(post("/api/v1/compressions").header("Idempotency-Key", "wrong-app")
                .contentType(MediaType.APPLICATION_JSON).content(completed("run_real", "10066", "TEST")))
                .andExpect(status().isNotFound());
    }

    @Test void testEvidenceCanDescribeAFileBeyondTheFakeTop50Snapshot() throws Exception {
        String body = completed("run_fake", "10066", "TEST").replace("a.mp4", "candidate-outside-top50.mp4");
        create("outside-top50", body);
        mvc.perform(get("/api/v1/compressions").param("appId", "10066").param("runId", "run_fake")
                .param("fileKey", "candidate-outside-top50.mp4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/compressions").param("appId", "10066").param("runId", "another-run"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test void processingTransitionsOnceAndRejectsStaleUpdate() throws Exception {
        var row = create("processing", """
                {"runId":"run_fake","appId":"10066","fileKey":"a.mp4","evidenceSource":"TEST","status":"PROCESSING","handler":"operator","remark":"排队中"}
                """);
        String id = row.path("id").asText();
        mvc.perform(put("/api/v1/compressions/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                {"appId":"10066","version":1,"status":"COMPRESSED","originalSizeBytes":"100000000",
                "originalSizeSource":"MANUAL_TEST","originalSizeEvidence":"报告 A","compressedVersion":"v2",
                "compressedSizeBytes":"60000000","quality":{"metric":"SSIM","value":0.96,"evidenceNote":"报告 Q"},"handler":"operator",
                "remark":"完成","compressedAt":"2026-10-06T02:00:00Z"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.compressionThresholdPassed").value(true));
        mvc.perform(put("/api/v1/compressions/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"appId\":\"10066\",\"version\":1,\"remark\":\"stale\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(put("/api/v1/compressions/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"appId\":\"10066\",\"version\":2,\"compressedSizeBytes\":\"30000000\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test void invalidEvidenceAndAbsentSchemaCannotProduceSuccess() throws Exception {
        mvc.perform(post("/api/v1/compressions").header("Idempotency-Key", "bad")
                .contentType(MediaType.APPLICATION_JSON).content(completed("run_fake", "10066", "TEST").replace("60000000", "-1")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/compressions").header("Idempotency-Key", "bad2")
                .contentType(MediaType.APPLICATION_JSON).content(completed("run_fake", "10066", "TEST").replace("MANUAL_TEST", "UNSUPPORTED")))
                .andExpect(status().isUnprocessableEntity());
        jdbc.execute("DROP TABLE compression_record");
        mvc.perform(get("/api/v1/compressions").param("appId", "10066"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("COMPRESSION_STORAGE_UNAVAILABLE"));
    }
}
