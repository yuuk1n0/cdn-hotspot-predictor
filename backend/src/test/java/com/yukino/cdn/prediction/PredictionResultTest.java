package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PredictionResultTest {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    static PredictionTask task() {
        var task = new PredictionTask();
        task.taskId = "task_one"; task.pythonRunId = "run_one"; task.datasetSource = "REAL";
        task.request = new PredictionRequest("ds_one", "10350", LocalDate.of(2026, 10, 6), 7, "contract-demo-v1");
        return task;
    }

    static ObjectNode page(PredictionTask task, int total) {
        var page = MAPPER.createObjectNode();
        page.put("taskId", task.taskId).put("runId", task.pythonRunId).put("datasetId", task.request.datasetId())
                .put("appId", task.request.appId()).put("cutoffDate", task.request.cutoffDate().toString())
                .put("horizonDays", task.request.horizonDays()).put("modelVersion", task.request.modelVersion())
                .put("predictionStartDate", task.request.cutoffDate().plusDays(1).toString())
                .put("predictionEndDate", task.request.cutoffDate().plusDays(task.request.horizonDays()).toString())
                .put("datasetSource", task.datasetSource).put("predictionSource", "MOCK")
                .put("generatedAt", "2026-10-20T08:00:00Z").put("page", 1).put("pageSize", 50).put("total", total);
        var items = page.putArray("items");
        for (int i = 0; i < Math.min(50, total); i++) {
            items.addObject().put("rank", i + 1).put("fileKey", String.format("file-%03d", i)).put("predictedBytes", "10000000000000001")
                    .putNull("score").put("reason", "模拟链路结果");
        }
        return page;
    }

    @Test
    void selectsOnlyFirstFiftyAndSumsExactWindowBytesWithoutMultiplyingSevenAgain() {
        var task = task();
        var result = PredictionResult.build(page(task, 930), task);
        assertEquals(50, result.path("items").size());
        assertEquals(930, result.path("candidateCount").intValue());
        assertEquals(50, result.path("top50Count").intValue());
        assertEquals("500000000000000050", result.path("top50PredictedBytes").textValue());
        assertEquals("10000000000000001", result.path("items").get(0).path("predictedBytes").textValue());
        assertEquals("REAL", result.path("datasetSource").textValue());
        assertEquals("MOCK", result.path("predictionSource").textValue());
        assertTrue(result.path("items").get(0).path("fileSizeBytes").isNull());
        assertTrue(result.path("items").get(0).path("recentActualBytes").isNull());
        assertTrue(result.path("items").get(0).path("compressionStatus").isNull());
        assertTrue(result.path("hotspotTrafficRatio").path("value").isNull());
        assertEquals("MOCK_RESULT_AND_NO_MATCHING_TOTAL", result.path("hotspotTrafficRatio").path("reasonCode").textValue());
    }

    @Test
    void shortAndEmptyResultsAreNotPaddedAndDailyWindowIsNextNaturalDay() {
        var task = task();
        task.request = new PredictionRequest("ds_one", "10350", LocalDate.of(2026, 10, 6), 1, "contract-demo-v1");
        var shortResult = PredictionResult.build(page(task, 2), task);
        assertEquals(2, shortResult.path("items").size());
        assertEquals("2026-10-07", shortResult.path("predictionEndDate").textValue());
        var empty = PredictionResult.build(page(task, 0), task);
        assertEquals("0", empty.path("top50PredictedBytes").textValue());
        assertEquals(0, empty.path("items").size());
    }

    @Test
    void mismatchedMetadataPaginationAndIncompletePagesAreRejectedNotRepaired() {
        List<Consumer<ObjectNode>> changes = List.of(
                p -> p.put("taskId", "other"), p -> p.put("runId", "other"), p -> p.put("datasetId", 123),
                p -> p.put("datasetId", "other"), p -> p.put("appId", "10066"), p -> p.put("modelVersion", "other"),
                p -> p.put("cutoffDate", "2026-10-05"), p -> p.put("horizonDays", 7.0), p -> p.put("horizonDays", 1),
                p -> p.put("predictionStartDate", "2026-10-06"), p -> p.put("predictionEndDate", "2026-10-14"),
                p -> p.put("datasetSource", "FAKE"), p -> p.putNull("predictionSource"), p -> p.put("predictionSource", "unknown"),
                p -> p.put("generatedAt", "not-time"), p -> p.put("page", 2), p -> p.put("pageSize", 200),
                p -> p.put("total", -1), p -> p.put("total", "2"), p -> p.put("total", 3));
        for (var change : changes) {
            var task = task(); var page = page(task, 2); change.accept(page);
            assertEquals("INVALID_RESULT", assertThrows(PredictionProblem.class, () -> PredictionResult.build(page, task)).code(), page.toString());
        }
    }

    @Test
    void duplicateKeysBadRanksNegativeNumericOrScientificByteValuesAndWrongOrderFail() {
        for (String value : List.of("-1", "NaN", "1e3", "", "01")) {
            var task = task(); var page = page(task, 2);
            ((ObjectNode) page.path("items").get(0)).put("predictedBytes", value);
            assertThrows(PredictionProblem.class, () -> PredictionResult.build(page, task));
        }
        List<Consumer<ObjectNode>> changes = List.of(
                p -> ((ObjectNode) p.path("items").get(0)).put("predictedBytes", 123),
                p -> ((ObjectNode) p.path("items").get(1)).put("rank", 3),
                p -> ((ObjectNode) p.path("items").get(1)).put("fileKey", "file-000"),
                p -> ((ObjectNode) p.path("items").get(0)).put("fileKey", "z"),
                p -> ((ObjectNode) p.path("items").get(1)).put("predictedBytes", "10000000000000002"),
                p -> ((ObjectNode) p.path("items").get(0)).put("score", "0.5"));
        for (var change : changes) {
            var task = task(); var page = page(task, 2); change.accept(page);
            assertThrows(PredictionProblem.class, () -> PredictionResult.build(page, task));
        }
    }

    @Test
    void exactDecimalsUnicodeTiesAndOpaqueIdentitiesRemainStable() {
        var task = task(); var page = page(task, 2);
        ((ObjectNode) page.path("items").get(0)).put("fileKey", "\uE000").put("predictedBytes", "0.2");
        ((ObjectNode) page.path("items").get(1)).put("fileKey", "\uD800\uDC00").put("predictedBytes", "0.20");
        var result = PredictionResult.build(page, task);
        assertEquals("0.4", result.path("top50PredictedBytes").textValue());
        page = page(task, 2);
        ((ObjectNode) page.path("items").get(0)).put("fileKey", "host/a");
        ((ObjectNode) page.path("items").get(1)).put("fileKey", "https://host/a");
        assertEquals(2, PredictionResult.build(page, task).path("items").size());
    }
}
