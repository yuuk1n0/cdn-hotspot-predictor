package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public record PredictionRequest(String datasetId, String appId, LocalDate cutoffDate, int horizonDays, String modelVersion) {
    public PredictionRequest {
        validateText(datasetId, 128, "datasetId");
        validateText(appId, 32, "appId");
        validateText(modelVersion, 64, "modelVersion");
        if (cutoffDate == null || cutoffDate.getYear() < 1 || cutoffDate.getYear() > 9998) {
            throw invalid("cutoffDate 必须为可预测后续窗口的有效日期。");
        }
        if (horizonDays != 1 && horizonDays != 7) throw invalid("horizonDays 仅支持 1 或 7，表示截止日之后连续自然日。");
    }

    public static PredictionRequest fromJson(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid("预测请求必须为 JSON 对象。");
        JsonNode horizon = body.path("horizonDays");
        if (!horizon.isIntegralNumber() || !horizon.canConvertToInt()) throw invalid("horizonDays 必须为整数 1 或 7。");
        try {
            String cutoff = text(body, "cutoffDate");
            if (!cutoff.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid("cutoffDate 必须为 YYYY-MM-DD。");
            return new PredictionRequest(text(body, "datasetId"), text(body, "appId"), LocalDate.parse(cutoff),
                    horizon.intValue(), text(body, "modelVersion"));
        } catch (DateTimeParseException exception) {
            throw invalid("cutoffDate 不是有效日期。");
        }
    }

    private static String text(JsonNode body, String key) {
        if (!body.path(key).isTextual()) throw invalid(key + " 必须为字符串。");
        return body.path(key).textValue();
    }

    private static void validateText(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(name + " 必须为非空字符串，长度不超过 " + max + "，且不包含首尾空白或控制字符。");
        }
    }

    static PredictionProblem invalid(String message) { return new PredictionProblem(422, "INVALID_REQUEST", message); }
}
