package com.yukino.cdn.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yukino.cdn.prediction.PredictionProblem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

public final class OperationsSupport {
    private OperationsSupport() {}
    public static PredictionProblem problem(int status, String code, String message) { return new PredictionProblem(status, code, message); }
    public static String text(JsonNode body, String field, int max) {
        JsonNode value = body.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max || value.asText().chars().anyMatch(c -> c < 32 || c == 127))
            throw problem(422, "INVALID_OPERATIONS_REQUEST", field + "须为有效字符串。");
        return value.textValue();
    }
    public static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) throw problem(422, "INVALID_APP_ID", "AppID须为1至32个字母、数字、下划线或连字符。");
        return value;
    }
    public static String source(String value) {
        if (!java.util.Set.of("REAL", "TEST").contains(value == null ? "" : value)) throw problem(422, "INVALID_EVIDENCE_SOURCE", "证据范围须为REAL或TEST。");
        return value;
    }
    public static BigDecimal decimal(JsonNode body, String name, boolean integer) {
        JsonNode value = body.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > 256 || !value.asText().matches(integer ? "(?:0|[1-9][0-9]*)" : "(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))
            throw problem(422, "INVALID_SIZE_OR_COST", name + "须为非负十进制字符串。");
        return new BigDecimal(value.asText());
    }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String fingerprint(JsonNode input) { return hash(canonical(input).toString()); }
    private static JsonNode canonical(JsonNode input) {
        if (input.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var ordered = new TreeMap<String, JsonNode>(); input.fields().forEachRemaining(e -> ordered.put(e.getKey(), canonical(e.getValue())));
            ordered.forEach(result::set); return result;
        }
        if (input.isArray()) { var result = JsonNodeFactory.instance.arrayNode(); input.forEach(i -> result.add(canonical(i))); return result; }
        return input;
    }
}
