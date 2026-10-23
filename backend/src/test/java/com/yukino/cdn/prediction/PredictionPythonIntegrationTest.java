package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.yukino.cdn.prediction.PredictionProcessRestartTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real Python + real Java processes, temporary H2/data directories, no business MySQL. */
@EnabledIfEnvironmentVariable(named = "PREDICTOR_TEST_PYTHON", matches = ".+")
class PredictionPythonIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realV1DailyAndWeeklyPublishPersistAndRejectUnsupportedRequests(@TempDir Path directory) throws Exception {
        Path project = Path.of("").toAbsolutePath().getParent();
        int pythonPort = freePort(), javaPort = freePort();
        RestClient python = http(pythonPort), java = http(javaPort);
        String database = "jdbc:h2:file:" + directory.resolve("business") + ";MODE=MySQL";
        var source = new DriverManagerDataSource(database, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("prediction-test-schema.sql"),
                new ClassPathResource("legacy-top50-test-schema.sql"), new ClassPathResource("data.sql")).execute(source);
        Path pythonLog = directory.resolve("python.log"), javaLog = directory.resolve("java.log");
        Process pythonProcess = startPython(project, directory, pythonPort, 0, pythonLog, "MODEL", "hotspot-simple-v1");
        Process javaProcess = null;
        try {
            awaitStartup(pythonProcess, python, pythonLog);
            var form = new LinkedMultiValueMap<String, Object>();
            form.add("file", new FileSystemResource(syntheticHistory(directory.resolve("model-fixture.csv"))));
            form.add("mapping", "{\"fileKey\":\"文件标识\",\"date\":\"日期\",\"trafficBytes\":\"当日流量（B）\",\"appId\":\"AppID\"}");
            form.add("datasetType", "FILE_DAILY"); form.add("datasetSource", "REAL"); form.add("sourceUnit", "B");
            var receipt = python.post().uri("/api/v1/imports").header("Idempotency-Key", "model-v1-upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toEntity(JsonNode.class);
            assertEquals(202, receipt.getStatusCode().value());
            JsonNode imported = awaitTerminal(python, "/api/v1/imports/" + receipt.getBody().path("importTaskId").textValue(), 20);
            assertEquals("SUCCEEDED", imported.path("status").textValue(), imported.toString());
            String datasetId = imported.path("datasetId").textValue();
            javaProcess = start(database, javaPort, "http://127.0.0.1:" + pythonPort, javaLog);
            awaitStartup(javaProcess, java, javaLog);
            JsonNode fakeBefore = java.get().uri("/api/v1/top50?page=1&pageSize=50").retrieve().body(JsonNode.class);
            assertEquals("FAKE", fakeBefore.path("dataSource").textValue());
            assertEquals(32, fakeBefore.path("total").intValue());
            ObjectNode body = mapper.createObjectNode().put("datasetId", datasetId).put("appId", "10350")
                    .put("cutoffDate", "2026-10-06").put("horizonDays", 1).put("modelVersion", "hotspot-simple-v1");
            String dailyId = submit(java, body, "model-v1-daily").path("taskId").textValue();
            JsonNode dailyTask = awaitTerminal(java, "/api/v1/tasks/" + dailyId, 30);
            JsonNode daily = assertRealSnapshot(java, python, dailyTask, "2026-10-07");
            assertEquals(dailyId, submit(java, body, "model-v1-daily").path("taskId").textValue());
            ObjectNode weeklyBody = body.deepCopy().put("horizonDays", 7);
            String weeklyId = submit(java, weeklyBody, "model-v1-weekly").path("taskId").textValue();
            JsonNode weeklyTask = awaitTerminal(java, "/api/v1/tasks/" + weeklyId, 30);
            JsonNode weekly = assertRealSnapshot(java, python, weeklyTask, "2026-10-13");

            String wrongModelId = submit(java, body.deepCopy().put("modelVersion", "unapproved-model"), "model-v1-wrong-model")
                    .path("taskId").textValue();
            assertFailedWithoutPublishedMode(awaitTerminal(java, "/api/v1/tasks/" + wrongModelId, 20), "MODEL_VERSION_UNSUPPORTED");
            String wrongAppId = submit(java, body.deepCopy().put("appId", "10066"), "model-v1-wrong-app")
                    .path("taskId").textValue();
            assertFailedWithoutPublishedMode(awaitTerminal(java, "/api/v1/tasks/" + wrongAppId, 20), "APP_MISMATCH");
            assertEquals(fakeBefore, java.get().uri("/api/v1/top50?page=1&pageSize=50").retrieve().body(JsonNode.class));

            stop(pythonProcess); pythonProcess = null;
            stop(javaProcess);
            javaProcess = start(database, javaPort, "http://127.0.0.1:" + pythonPort, javaLog);
            awaitStartup(javaProcess, java, javaLog);
            assertEquals(daily, java.get().uri("/api/v1/predictions/{id}", dailyTask.path("runId").textValue()).retrieve().body(JsonNode.class));
            assertEquals(weekly, java.get().uri("/api/v1/predictions/{id}", weeklyTask.path("runId").textValue()).retrieve().body(JsonNode.class));
            assertEquals(dailyTask, java.get().uri("/api/v1/tasks/{id}", dailyId).retrieve().body(JsonNode.class));
            assertEquals(weeklyTask, java.get().uri("/api/v1/tasks/{id}", weeklyId).retrieve().body(JsonNode.class));
            assertEquals(dailyId, submit(java, body, "model-v1-daily").path("taskId").textValue());
            assertEquals(weeklyId, submit(java, weeklyBody, "model-v1-weekly").path("taskId").textValue());
            assertEquals(fakeBefore, java.get().uri("/api/v1/top50?page=1&pageSize=50").retrieve().body(JsonNode.class));
            stop(javaProcess); javaProcess = null;
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
            assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM prediction_run WHERE prediction_source = 'MODEL'", Long.class));
            assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM prediction_run", Long.class));
            assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM prediction_task", Long.class));
            System.out.println(mapper.writeValueAsString(Map.of("case", "real-java-python-v1", "datasetId", datasetId,
                    "dailyTaskId", dailyId, "weeklyTaskId", weeklyId, "candidateCount", 60, "top50Count", 50,
                    "dailyTop50PredictedBytes", daily.path("top50PredictedBytes").textValue(),
                    "weeklyTop50PredictedBytes", weekly.path("top50PredictedBytes").textValue())));
        } finally {
            if (javaProcess != null) stop(javaProcess);
            if (pythonProcess != null) stop(pythonProcess);
        }
    }

    private JsonNode assertRealSnapshot(RestClient java, RestClient python, JsonNode task, String endDate) {
        assertEquals("SUCCEEDED", task.path("status").textValue(), task.toString());
        assertEquals("MODEL", task.path("predictionSource").textValue());
        assertEquals("REAL", task.path("resultMode").textValue());
        String runId = task.path("runId").textValue();
        JsonNode result = java.get().uri("/api/v1/predictions/{id}", runId).retrieve().body(JsonNode.class);
        JsonNode page = python.get().uri("/api/v1/predictions/{id}/items?page=1&pageSize=50", runId).retrieve().body(JsonNode.class);
        assertEquals("MODEL", result.path("predictionSource").textValue());
        assertEquals("REAL", result.path("resultMode").textValue());
        assertEquals("REAL", result.path("datasetSource").textValue());
        assertEquals("hotspot-simple-v1", result.path("modelVersion").textValue());
        assertEquals(60, result.path("candidateCount").intValue());
        assertEquals(50, result.path("top50Count").intValue());
        assertEquals(page.path("items"), result.path("items"));
        BigDecimal expected = BigDecimal.ZERO;
        for (JsonNode item : page.path("items")) expected = expected.add(new BigDecimal(item.path("predictedBytes").textValue()));
        assertEquals(expected.toPlainString(), result.path("top50PredictedBytes").textValue());
        assertEquals("2026-10-07", result.path("predictionStartDate").textValue());
        assertEquals(endDate, result.path("predictionEndDate").textValue());
        assertEquals(50, result.path("items").size());
        for (int index = 0; index < 50; index++) {
            JsonNode expected = page.path("items").get(index), actual = result.path("items").get(index);
            for (String field : List.of("rank", "fileKey", "predictedBytes", "score", "reason")) {
                assertEquals(expected.path(field), actual.path(field), "row " + index + " " + field);
            }
        }
        assertTrue(result.path("hotspotTrafficRatio").path("value").isNull());
        assertEquals("NO_MATCHING_TOTAL", result.path("hotspotTrafficRatio").path("reasonCode").textValue());
        return result;
    }

    private void assertFailedWithoutPublishedMode(JsonNode task, String errorCode) {
        assertEquals("FAILED", task.path("status").textValue(), task.toString());
        assertEquals(errorCode, task.path("error").path("code").textValue());
        assertTrue(task.path("runId").isNull());
        assertTrue(task.path("predictionSource").isNull());
        assertTrue(task.path("resultMode").isNull());
    }

    @Test
    void realShortAndHundredSecondMockPublishTop50AndSurviveJavaRestart(@TempDir Path directory) throws Exception {
        Path project = Path.of("").toAbsolutePath().getParent();
        int pythonPort = freePort(), javaPort = freePort();
        RestClient python = http(pythonPort), java = http(javaPort);
        String database = "jdbc:h2:file:" + directory.resolve("business") + ";MODE=MySQL";
        var source = new DriverManagerDataSource(database, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("prediction-test-schema.sql"),
                new ClassPathResource("legacy-top50-test-schema.sql"), new ClassPathResource("data.sql")).execute(source);
        Path pythonLog = directory.resolve("python.log"), javaLog = directory.resolve("java.log");
        Process pythonProcess = startPython(project, directory, pythonPort, 0, pythonLog);
        Process javaProcess = null;
        try {
            awaitStartup(pythonProcess, python, pythonLog);
            var form = new LinkedMultiValueMap<String, Object>();
            form.add("file", new FileSystemResource(syntheticHistory(directory.resolve("mock-fixture.csv"))));
            form.add("mapping", "{\"fileKey\":\"文件标识\",\"date\":\"日期\",\"trafficBytes\":\"当日流量（B）\",\"appId\":\"AppID\"}");
            form.add("datasetType", "FILE_DAILY"); form.add("datasetSource", "REAL"); form.add("sourceUnit", "B");
            var receipt = python.post().uri("/api/v1/imports").header("Idempotency-Key", "step5-real-upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toEntity(JsonNode.class);
            assertEquals(202, receipt.getStatusCode().value());
            String importId = receipt.getBody().path("importTaskId").textValue();
            JsonNode imported = awaitTerminal(python, "/api/v1/imports/" + importId, 10);
            assertEquals("SUCCEEDED", imported.path("status").textValue(), imported.toString());
            String datasetId = imported.path("datasetId").textValue();
            javaProcess = start(database, javaPort, "http://127.0.0.1:" + pythonPort, javaLog);
            awaitStartup(javaProcess, java, javaLog);
            JsonNode fakeBefore = java.get().uri("/api/v1/top50?page=1&pageSize=50").retrieve().body(JsonNode.class);
            assertEquals("FAKE", fakeBefore.path("dataSource").textValue());
            assertEquals(32, fakeBefore.path("total").intValue());
            ObjectNode body = mapper.createObjectNode().put("datasetId", datasetId).put("appId", "10350")
                    .put("cutoffDate", "2026-10-06").put("horizonDays", 7).put("modelVersion", "contract-demo-v1");
            JsonNode accepted = submit(java, body, "step5-short");
            String shortId = accepted.path("taskId").textValue();
            JsonNode shortTask = awaitTerminal(java, "/api/v1/tasks/" + shortId, 20);
            assertEquals("SUCCEEDED", shortTask.path("status").textValue(), shortTask.toString());
            JsonNode shortSnapshot = assertSnapshot(java, python, shortTask, datasetId);
            assertEquals(shortId, submit(java, body, "step5-short").path("taskId").textValue());

            stop(pythonProcess);
            pythonProcess = startPython(project, directory, pythonPort, 100, pythonLog);
            awaitStartup(pythonProcess, python, pythonLog);
            long started = System.nanoTime();
            accepted = submit(java, body, "step5-long");
            double submitSeconds = (System.nanoTime() - started) / 1e9;
            assertTrue(submitSeconds < 3, "submission must not wait for 100-second compute");
            String longId = accepted.path("taskId").textValue();
            JsonNode running = awaitComputing(java, longId);
            assertTrue(running.path("runId").isNull());
            stop(javaProcess);
            javaProcess = start(database, javaPort, "http://127.0.0.1:" + pythonPort, javaLog);
            awaitStartup(javaProcess, java, javaLog);
            assertEquals(running.path("deadlineAt"), java.get().uri("/api/v1/tasks/{id}", longId).retrieve().body(JsonNode.class).path("deadlineAt"));
            JsonNode longTask = awaitTerminal(java, "/api/v1/tasks/" + longId, 130);
            assertEquals("SUCCEEDED", longTask.path("status").textValue(), longTask.toString());
            double elapsed = (System.nanoTime() - started) / 1e9;
            assertTrue(elapsed >= 99.5 && elapsed < 150, "real 100-second delay must use the same contract");
            JsonNode longSnapshot = assertSnapshot(java, python, longTask, datasetId);
            assertEquals(shortSnapshot.path("items"), longSnapshot.path("items"));
            assertEquals("UP", java.get().uri("/api/v1/health").retrieve().body(JsonNode.class).path("status").textValue());

            // Completed Java snapshots must remain queryable with Python offline and Java restarted.
            stop(pythonProcess); pythonProcess = null;
            stop(javaProcess);
            javaProcess = start(database, javaPort, "http://127.0.0.1:" + pythonPort, javaLog);
            awaitStartup(javaProcess, java, javaLog);
            assertEquals(longSnapshot, java.get().uri("/api/v1/predictions/{id}", longTask.path("runId").textValue()).retrieve().body(JsonNode.class));
            assertEquals(shortSnapshot, java.get().uri("/api/v1/predictions/{id}", shortTask.path("runId").textValue()).retrieve().body(JsonNode.class));
            assertEquals(fakeBefore, java.get().uri("/api/v1/top50?page=1&pageSize=50").retrieve().body(JsonNode.class));
            assertEquals("SUCCEEDED", java.get().uri("/api/v1/tasks/{id}", longId).retrieve().body(JsonNode.class).path("status").textValue());
            assertEquals(longId, submit(java, body, "step5-long").path("taskId").textValue());
            var reopened = new org.springframework.jdbc.core.JdbcTemplate(source);
            stop(javaProcess); javaProcess = null;
            assertEquals(2L, reopened.queryForObject("SELECT COUNT(*) FROM prediction_run", Long.class));
            System.out.println(mapper.writeValueAsString(Map.of("case", "real-java-python", "datasetId", datasetId,
                    "shortTaskId", shortId, "longTaskId", longId, "longRunId", longTask.path("runId").textValue(),
                    "candidates", 60, "top50Count", 50, "top50PredictedBytes", longSnapshot.path("top50PredictedBytes").textValue(),
                    "submitSeconds", submitSeconds, "elapsedSeconds", elapsed)));
        } finally {
            if (javaProcess != null) stop(javaProcess);
            if (pythonProcess != null) stop(pythonProcess);
        }
    }

    private JsonNode submit(RestClient http, JsonNode body, String key) {
        var response = http.post().uri("/api/v1/predictions").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(JsonNode.class);
        assertEquals(202, response.getStatusCode().value());
        return response.getBody();
    }

    private JsonNode assertSnapshot(RestClient java, RestClient python, JsonNode task, String datasetId) {
        String runId = task.path("runId").textValue();
        JsonNode result = java.get().uri("/api/v1/predictions/{id}", runId).retrieve().body(JsonNode.class);
        JsonNode page = python.get().uri("/api/v1/predictions/{id}/items?page=1&pageSize=50", runId).retrieve().body(JsonNode.class);
        assertEquals(datasetId, result.path("datasetId").textValue());
        assertEquals("REAL", result.path("datasetSource").textValue());
        assertEquals("MOCK", result.path("predictionSource").textValue());
        assertEquals(60, result.path("candidateCount").intValue());
        assertEquals(50, result.path("top50Count").intValue());
        assertEquals(page.path("items"), result.path("items"));
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode item : page.path("items")) sum = sum.add(new BigDecimal(item.path("predictedBytes").textValue()));
        assertEquals(sum.toPlainString(), result.path("top50PredictedBytes").textValue());
        assertTrue(result.path("hotspotTrafficRatio").path("value").isNull());
        return result;
    }

    private static JsonNode awaitTerminal(RestClient http, String path, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode task = http.get().uri(path).retrieve().body(JsonNode.class);
            if (task.path("status").asText().equals("SUCCEEDED") || task.path("status").asText().equals("FAILED")) return task;
            Thread.sleep(250);
        }
        throw new AssertionError("task did not terminate: " + path);
    }

    private static JsonNode awaitComputing(RestClient http, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode task = http.get().uri("/api/v1/tasks/{id}", id).retrieve().body(JsonNode.class);
            assertNotEquals("FAILED", task.path("status").asText(), task.toString());
            if (task.path("stage").asText().equals("COMPUTING")) return task;
            Thread.sleep(100);
        }
        throw new AssertionError("task did not start computing");
    }

    private static RestClient http(int port) {
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(1000); factory.setReadTimeout(5000);
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port).requestFactory(factory).build();
    }

    private static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }

    private static Path syntheticHistory(Path path) throws Exception {
        var content = new StringBuilder("文件标识,日期,当日流量（B）,AppID\n");
        LocalDate start = LocalDate.of(2026, 7, 9);
        for (int day = 0; day < 90; day++) {
            for (int file = 0; file < 60; file++) {
                content.append("synthetic/file-").append(String.format("%03d", file)).append(',')
                        .append(start.plusDays(day)).append(',').append(1000 + day + file).append(",10350\n");
            }
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static Process startPython(Path project, Path directory, int port, int delay, Path log) throws Exception {
        return startPython(project, directory, port, delay, log, "MOCK", "contract-demo-v1");
    }

    private static Process startPython(Path project, Path directory, int port, int delay, Path log, String mode, String version) throws Exception {
        var builder = new ProcessBuilder(System.getenv("PREDICTOR_TEST_PYTHON"), "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", String.valueOf(port))
                .directory(project.resolve("predictor").toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("PREDICTOR_DATA_DIR", directory.resolve("python-data").toString());
        builder.environment().put("PREDICTION_MODE", mode); builder.environment().put("PREDICTION_MODEL_VERSION", version);
        builder.environment().put("MOCK_DELAY_SECONDS", String.valueOf(delay)); builder.environment().put("PREDICTION_TASK_TIMEOUT_SECONDS", "180");
        return builder.start();
    }
}
