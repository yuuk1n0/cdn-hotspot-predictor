package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java HTTP + JDBC; Python prediction responses are explicit test-only contract fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:prediction_http;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "app.prediction.worker-enabled=false"
})
class PredictionHttpIntegrationTest {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicReference<JsonNode> submitted = new AtomicReference<>();
    private static HttpServer pythonFixture;
    @Autowired TestRestTemplate http;
    @Autowired DataSource dataSource;
    @Autowired PredictionWorker worker;
    @Autowired PredictionSettings settings;

    @DynamicPropertySource
    static void pythonAddress(DynamicPropertyRegistry registry) throws IOException {
        pythonFixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pythonFixture.createContext("/api/v1/datasets/ds_http", exchange -> send(exchange, 200, """
                {"datasetId":"ds_http","status":"READY","datasetType":"FILE_DAILY","datasetSource":"REAL","sourceUnit":"B",
                "appIds":["10350"],"appDateRanges":{"10350":{"start":"2026-01-05","end":"2026-10-13"}}}
                """));
        pythonFixture.createContext("/api/v1/tasks/", exchange -> send(exchange, 404,
                "{\"error\":{\"code\":\"TASK_NOT_FOUND\",\"message\":\"not found\"}}"));
        pythonFixture.createContext("/api/v1/predictions", exchange -> {
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            submitted.set(body);
            send(exchange, 202, mapper.createObjectNode().put("taskId", body.path("taskId").textValue()).put("status", "PENDING").toString());
        });
        pythonFixture.start();
        registry.add("app.prediction.python-base-url", () -> "http://127.0.0.1:" + pythonFixture.getAddress().getPort());
    }

    @AfterAll
    static void stopFixture() { if (pythonFixture != null) pythonFixture.stop(0); }

    @Test
    void businessHttpSubmissionPersistsBeforeDispatchAndUsesProductionHttpClient() {
        new ResourceDatabasePopulator(new ClassPathResource("prediction-test-schema.sql")).execute(dataSource);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "http-one");
        HttpEntity<String> request = new HttpEntity<>("""
                {"datasetId":"ds_http","appId":"10350","cutoffDate":"2026-10-06","horizonDays":7,"modelVersion":"contract-demo-v1"}
                """, headers);
        ResponseEntity<JsonNode> accepted = http.postForEntity("/api/v1/predictions", request, JsonNode.class);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String taskId = accepted.getBody().path("taskId").textValue();
        assertNotNull(taskId);
        assertNull(submitted.get());
        assertEquals("PENDING", http.getForObject("/api/v1/tasks/" + taskId, JsonNode.class).path("status").textValue());
        assertEquals(180, settings.taskTimeout().toSeconds());
        worker.tick();
        JsonNode payload = submitted.get();
        assertNotNull(payload);
        assertEquals(taskId, payload.path("taskId").textValue());
        assertEquals("ds_http", payload.path("datasetId").textValue());
        assertEquals(7, payload.path("horizonDays").intValue());
        assertEquals(6, payload.size());
        JsonNode task = http.getForObject("/api/v1/tasks/" + taskId, JsonNode.class);
        assertEquals("RUNNING", task.path("status").textValue());
        assertEquals("COMPUTING", task.path("stage").textValue());
        assertTrue(task.path("runId").isNull());
        assertEquals(taskId, http.postForEntity("/api/v1/predictions", request, JsonNode.class).getBody().path("taskId").textValue());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
