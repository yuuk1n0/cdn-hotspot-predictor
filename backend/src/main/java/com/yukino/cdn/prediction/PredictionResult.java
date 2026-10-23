package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates the already-ranked Python page; never repairs order or recalculates predictions. */
final class PredictionResult {
    private PredictionResult() {}

    static ObjectNode build(JsonNode page, PredictionTask task) {
        require(page != null && page.isObject(), "结果必须为对象。");
        match(page, "runId", task.pythonRunId);
        if (page.has("taskId")) match(page, "taskId", task.taskId);
        match(page, "datasetId", task.request.datasetId());
        match(page, "appId", task.request.appId());
        match(page, "cutoffDate", task.request.cutoffDate().toString());
        match(page, "modelVersion", task.request.modelVersion());
        require(integer(page, "horizonDays") == task.request.horizonDays(), "预测跨度不匹配。");
        match(page, "predictionStartDate", task.request.cutoffDate().plusDays(1).toString());
        match(page, "predictionEndDate", task.request.cutoffDate().plusDays(task.request.horizonDays()).toString());
        match(page, "datasetSource", task.datasetSource);
        require(Set.of("REAL", "FAKE").contains(text(page, "datasetSource")), "数据来源无效。");
        String source = text(page, "predictionSource");
        require(Set.of("MOCK", "MODEL").contains(source), "预测来源无效。");
        String resultMode = "MODEL".equals(source) ? "REAL" : "MOCK";
        if (page.has("resultMode")) match(page, "resultMode", resultMode);
        try { Instant.parse(text(page, "generatedAt")); }
        catch (DateTimeParseException exception) { throw invalid("结果生成时间无效。"); }
        require(integer(page, "page") == 1 && integer(page, "pageSize") == 50, "结果分页不匹配。");
        int total = integer(page, "total");
        JsonNode items = page.path("items");
        require(total >= 0 && items.isArray() && items.size() == Math.min(50, total), "候选总数与完整第一页条数不匹配。");

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String key : List.of("runId", "datasetId", "appId", "cutoffDate", "horizonDays", "modelVersion",
                "predictionStartDate", "predictionEndDate", "datasetSource", "predictionSource", "generatedAt")) result.set(key, page.get(key));
        result.put("taskId", task.taskId).put("candidateCount", total).put("top50Count", items.size());
        result.put("resultMode", resultMode);
        JsonNode modelInfo = page.path("modelInfo");
        if (modelInfo.isObject()) {
            require(modelInfo.path("selectedMethod").isTextual() && !modelInfo.path("selectedMethod").asText().isBlank(),
                    "模型方法缺失。");
            require(modelInfo.path("selectedSpec").isObject(), "模型参数缺失。");
            require(modelInfo.path("parameterSource").isTextual() && !modelInfo.path("parameterSource").asText().isBlank(),
                    "模型参数来源缺失。");
            require(modelInfo.path("algorithmFingerprint").asText("").matches("[0-9a-f]{64}"), "算法摘要无效。");
            result.set("modelInfo", modelInfo.deepCopy());
        }
        var selected = result.putArray("items");
        Set<String> identities = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO, previousBytes = null;
        String previousKey = null;
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            require(item.isObject() && integer(item, "rank") == index + 1, "全局名次不连续。");
            String key = text(item, "fileKey");
            require(!key.isBlank() && key.indexOf('\0') < 0 && identities.add(key), "文件身份缺失或重复。");
            String bytesText = text(item, "predictedBytes");
            require(bytesText.length() <= 256 && bytesText.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"), "预测流量必须为非负十进制字符串 B。");
            BigDecimal bytes = new BigDecimal(bytesText);
            if (previousBytes != null) {
                int comparison = previousBytes.compareTo(bytes);
                // UTF-8 unsigned order matches Python's Unicode code-point ordering, including supplementary characters.
                require(comparison > 0 || (comparison == 0 && Arrays.compareUnsigned(previousKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)) < 0), "结果未按流量降序和稳定文件身份排序。");
            }
            JsonNode score = item.path("score"), reason = item.path("reason");
            require(score.isMissingNode() || score.isNull() || (score.isNumber() && Double.isFinite(score.doubleValue())), "排序分数必须为有限数值或 null。");
            require(reason.isMissingNode() || reason.isNull() || reason.isTextual(), "推荐理由必须为字符串或 null。");
            ObjectNode row = selected.addObject().put("rank", index + 1).put("fileKey", key).put("predictedBytes", bytesText);
            row.set("score", score.isMissingNode() ? JsonNodeFactory.instance.nullNode() : score);
            row.set("reason", reason.isMissingNode() ? JsonNodeFactory.instance.nullNode() : reason);
            // There is no confirmed external file-state mapping. Never borrow values from Fake prototype rows.
            for (String field : List.of("fileType", "fileSizeBytes", "recentActualBytes", "compressionStatus")) row.putNull(field);
            sum = sum.add(bytes); previousBytes = bytes; previousKey = key;
        }
        String sumText = sum.stripTrailingZeros().toPlainString();
        result.put("top50PredictedBytes", sumText);
        result.putObject("hotspotTrafficRatio").put("metricBasis", "PREDICTED_TOP50_FORECAST").putNull("value")
                .put("numeratorBytes", sumText).putNull("denominatorBytes").put("numeratorSource", source).putNull("denominatorSource")
                .put("reasonCode", source.equals("MOCK") ? "MOCK_RESULT_AND_NO_MATCHING_TOTAL" : "NO_MATCHING_TOTAL");
        return result;
    }

    private static String text(JsonNode node, String name) {
        require(node.path(name).isTextual(), name + " 必须为字符串。");
        return node.path(name).textValue();
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = node.path(name);
        require(value.isIntegralNumber() && value.canConvertToInt(), name + " 必须为有效整数。");
        return value.intValue();
    }

    private static void match(JsonNode node, String key, String expected) {
        require(expected != null && expected.equals(text(node, key)), key + " 与原任务不匹配。");
    }

    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static PredictionProblem invalid(String message) { return new PredictionProblem(422, "INVALID_RESULT", message); }
}
