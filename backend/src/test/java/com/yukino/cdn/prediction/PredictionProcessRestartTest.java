package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Starts/stops real Java processes; the only database is a temporary H2 file. */
class PredictionProcessRestartTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void restartRecoversTheSameTaskAndDeadlineWithoutDuplicatePythonSubmission(@TempDir Path directory) throws Exception {
        String databaseUrl = "jdbc:h2:file:" + directory.resolve("tasks") + ";MODE=MySQL";
        new ResourceDatabasePopulator(new ClassPathResource("prediction-test-schema.sql"))
                .execute(new DriverManagerDataSource(databaseUrl, "sa", ""));
        AtomicReference<String> pythonTaskId = new AtomicReference<>();
        AtomicInteger dispatches = new AtomicInteger();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/api/v1/datasets/ds_restart", exchange -> send(exchange, 200, """
                {"datasetId":"ds_restart","status":"READY","datasetType":"FILE_DAILY","datasetSource":"REAL","sourceUnit":"B",
                "appIds":["10350"],"appDateRanges":{"10350":{"start":"2026-01-05","end":"2026-10-13"}}}
                """));
        fixture.createContext("/api/v1/tasks/", exchange -> {
            if (pythonTaskId.get() == null) send(exchange, 404, "{}");
            else send(exchange, 200, mapper.createObjectNode().put("taskId", pythonTaskId.get()).put("status", "RUNNING").toString());
        });
        fixture.createContext("/api/v1/predictions", exchange -> {
            pythonTaskId.set(mapper.readTree(exchange.getRequestBody()).path("taskId").textValue());
            dispatches.incrementAndGet();
            send(exchange, 202, mapper.createObjectNode().put("taskId", pythonTaskId.get()).put("status", "PENDING").toString());
        });
        fixture.start();
        try {
            int port;
            try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(1000); factory.setReadTimeout(3000);
            var http = RestClient.builder().baseUrl("http://127.0.0.1:" + port).requestFactory(factory).build();
            String body = """
                    {"datasetId":"ds_restart","appId":"10350","cutoffDate":"2026-10-06","horizonDays":1,"modelVersion":"contract-demo-v1"}
                    """;
            String pythonUrl = "http://127.0.0.1:" + fixture.getAddress().getPort();
            JsonNode first;
            Process process = start(databaseUrl, port, pythonUrl, directory.resolve("first.log"));
            try {
                awaitStartup(process, http, directory.resolve("first.log"));
                var accepted = http.post().uri("/api/v1/predictions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "process-restart").body(body).retrieve().toEntity(JsonNode.class);
                assertEquals(202, accepted.getStatusCode().value());
                first = awaitAttempt(http, accepted.getBody().path("taskId").textValue(), 1);
                assertEquals("COMPUTING", first.path("stage").textValue());
            } finally { stop(process); }
            process = start(databaseUrl, port, pythonUrl, directory.resolve("restarted.log"));
            try {
                awaitStartup(process, http, directory.resolve("restarted.log"));
                String id = first.path("taskId").textValue();
                JsonNode resumed = awaitAttempt(http, id, first.path("attemptCount").intValue() + 1);
                assertEquals("RUNNING", resumed.path("status").textValue());
                assertEquals(first.path("deadlineAt"), resumed.path("deadlineAt"));
                assertTrue(resumed.path("runId").isNull());
                JsonNode duplicate = http.post().uri("/api/v1/predictions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "process-restart").body(body).retrieve().body(JsonNode.class);
                assertEquals(id, duplicate.path("taskId").textValue());
                assertEquals(id, pythonTaskId.get());
                assertEquals(1, dispatches.get());
            } finally { stop(process); }
        } finally { fixture.stop(0); }
    }

    private static Process start(String databaseUrl, int port, String pythonUrl, Path log) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "com.yukino.cdn.CdnApplication",
                "--server.port=" + port, "--spring.datasource.url=" + databaseUrl, "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--app.prediction.python-base-url=" + pythonUrl,
                "--app.prediction.worker-enabled=true", "--app.prediction.worker-tick-ms=100")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void awaitStartup(Process process, RestClient http, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) fail(Files.readString(log));
            try { if (http.get().uri("/api/v1/health").retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful()) return; }
            catch (RestClientException ignored) {}
            Thread.sleep(50);
        }
        fail("Java startup timeout: " + Files.readString(log));
    }

    private static JsonNode awaitAttempt(RestClient http, String id, int minimum) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode task = http.get().uri("/api/v1/tasks/{id}", id).retrieve().body(JsonNode.class);
            if (task.path("status").asText().equals("RUNNING") && task.path("stage").asText().equals("COMPUTING")
                    && task.path("attemptCount").intValue() >= minimum) return task;
            assertNotEquals("FAILED", task.path("status").asText(), task.toString());
            Thread.sleep(50);
        }
        throw new AssertionError("Task did not resume: " + id);
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
