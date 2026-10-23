package com.yukino.cdn.compression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.*;
import static com.yukino.cdn.operations.OperationsSupport.*;

/** Evidence arithmetic only. Declared observations are never described as measured bills. */
public final class CompressionEvidence {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private CompressionEvidence() {}
    public static ObjectNode complete(JsonNode input, Clock clock) {
        var record = (ObjectNode) input.deepCopy();
        text(record, "handler", 128);
        BigDecimal original = decimal(record, "originalSizeBytes", true), compressed = decimal(record, "compressedSizeBytes", true);
        if (original == null || compressed == null || original.signum() <= 0 || compressed.signum() <= 0 || compressed.compareTo(original) > 0)
            throw problem(422, "INVALID_SIZE", "完成登记须有正整数原/后大小，压缩后不能超过原大小。");
        if (!(record.path("compressedFileKey").isTextual() && !record.path("compressedFileKey").asText().isBlank())
                && !(record.path("compressedVersion").isTextual() && !record.path("compressedVersion").asText().isBlank()))
            throw problem(422, "MISSING_EVIDENCE", "须提供压缩后fileKey或版本。");
        try { if (Instant.parse(text(record, "compressedAt", 64)).isAfter(clock.instant())) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) { throw problem(422, "INVALID_COMPRESSED_AT", "压缩时间须为非未来的ISO时间。"); }
        JsonNode quality = record.path("quality"); text(quality, "evidenceNote", 2000);
        Boolean passed = switch (text(quality, "metric", 32)) {
            case "VMAF" -> metric(quality, 100).compareTo(new BigDecimal("90")) >= 0;
            case "SSIM" -> metric(quality, 1).compareTo(new BigDecimal("0.95")) >= 0;
            case "MANUAL_REVIEW" -> {
                if (!quality.path("passed").isBoolean()) throw problem(422, "MISSING_EVIDENCE", "人工复核须声明passed。");
                yield quality.path("passed").booleanValue();
            }
            case "UNKNOWN" -> null;
            default -> throw problem(422, "INVALID_QUALITY", "质量指标不受支持。");
        };
        BigDecimal ratio = BigDecimal.ONE.subtract(compressed.divide(original, MathContext.DECIMAL128));
        record.put("compressionRatio", ratio).put("compressionThresholdPassed", ratio.compareTo(new BigDecimal("0.3")) >= 0)
                .put("effectiveCompressionStatus", "PENDING_OBSERVATION");
        if (passed == null) record.putNull("qualityPassed"); else record.put("qualityPassed", passed);
        return record;
    }
    private static BigDecimal metric(JsonNode quality, int max) {
        JsonNode value = quality.path("value");
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || value.decimalValue().signum() < 0 || value.decimalValue().compareTo(BigDecimal.valueOf(max)) > 0)
            throw problem(422, "INVALID_QUALITY", "质量指标取值越界。");
        return value.decimalValue();
    }
    public static ObjectNode observe(JsonNode record, JsonNode input, Clock clock) {
        if (!record.path("status").asText().equals("COMPRESSED")) throw problem(409, "COMPRESSION_NOT_COMPLETED", "处理中记录尚不能登记完成后观测。");
        if (!record.path("appId").equals(input.path("appId")) || !record.path("evidenceSource").equals(input.path("evidenceSource")))
            throw problem(422, "APP_OR_SOURCE_MISMATCH", "观测App和来源须匹配处理记录。");
        int days = input.path("windowDays").asInt();
        if (!input.path("windowDays").isIntegralNumber() || (days != 7 && days != 30)) throw problem(422, "INVALID_OBSERVATION_WINDOW", "观察窗口须为7或30天。");
        LocalDate start = Instant.parse(record.path("compressedAt").asText()).atZone(SHANGHAI).toLocalDate().plusDays(1), end = start.plusDays(days - 1L);
        if (!start.toString().equals(input.path("startDate").asText()) || !end.toString().equals(input.path("endDate").asText()))
            throw problem(422, "INVALID_OBSERVATION_WINDOW", "窗口应从压缩次日开始并覆盖完整7/30天。");
        if (clock.instant().isBefore(end.plusDays(1).atStartOfDay(SHANGHAI).toInstant())) throw problem(409, "WINDOW_NOT_MATURE", "观察窗口尚未成熟，不能登记未来实测。");
        text(input, "evidenceNote", 2000);
        var result = (ObjectNode) input.deepCopy();
        var missing = result.putArray("missingFields");
        BigDecimal baseline = decimal(input, "baselineTrafficBytes", true), transcode = decimal(input, "transcodingCostYuan", false), storage = decimal(input, "storageCostYuan", false);
        JsonNode routed = input.path("routedShare"), accesses = input.path("actualAccessCount"), accessible = input.path("accessible");
        if (!routed.isNull() && !routed.isMissingNode() && (!routed.isNumber() || !Double.isFinite(routed.doubleValue()) || routed.decimalValue().signum() < 0 || routed.decimalValue().compareTo(BigDecimal.ONE) > 0))
            throw problem(422, "INVALID_ROUTED_SHARE", "承接比例须在0至1之间。");
        if (!accesses.isMissingNode() && !accesses.isNull() && (!accesses.isIntegralNumber() || accesses.bigIntegerValue().signum() < 0)) throw problem(422, "INVALID_ACCESS_COUNT", "访问次数须为非负整数。");
        if (!accessible.isMissingNode() && !accessible.isNull() && !accessible.isBoolean()) throw problem(422, "INVALID_ACCESSIBLE", "accessible须为boolean或null。");
        for (String key : new String[]{"baselineTrafficBytes", "routedShare", "actualAccessCount", "accessible", "transcodingCostYuan", "storageCostYuan", "qualityEvidenceNote"})
            if (!input.hasNonNull(key) || (input.path(key).isTextual() && input.path(key).asText().isBlank())) missing.add(key);
        if (record.path("qualityPassed").isNull() || record.path("qualityPassed").isMissingNode()) missing.add("qualityPassed");
        BigDecimal ratio = BigDecimal.ONE.subtract(decimal(record, "compressedSizeBytes", true).divide(decimal(record, "originalSizeBytes", true), MathContext.DECIMAL128));
        BigDecimal saved = baseline != null && routed.isNumber() ? baseline.multiply(routed.decimalValue()).multiply(ratio) : null;
        BigDecimal cdn = saved == null ? null : saved.divide(new BigDecimal("1000000000")).multiply(new BigDecimal("0.02"));
        BigDecimal net = cdn != null && transcode != null && storage != null ? cdn.subtract(transcode).subtract(storage) : null;
        putDecimal(result, "estimatedSavedBytes", saved, false); putDecimal(result, "estimatedCdnSavingYuan", cdn, true); putDecimal(result, "estimatedNetBenefitYuan", net, true);
        boolean fail = (accesses.isIntegralNumber() && accesses.bigIntegerValue().signum() == 0) || (routed.isNumber() && routed.decimalValue().signum() == 0)
                || (accessible.isBoolean() && !accessible.booleanValue()) || ratio.compareTo(new BigDecimal("0.3")) < 0
                || (record.path("qualityPassed").isBoolean() && !record.path("qualityPassed").booleanValue()) || (net != null && net.signum() <= 0);
        return result.put("compressionRatio", ratio).put("cdnUnitPriceYuanPerGB", "0.02").put("effectiveCompressionStatus", fail ? "FAIL" : missing.isEmpty() ? "PASS" : "UNKNOWN")
                .put("estimateBasis", "POST_OBSERVATION_ESTIMATE").put("isRealizedBillSaving", false).put("registeredAt", clock.instant().toString());
    }
    private static void putDecimal(ObjectNode node, String key, BigDecimal value, boolean money) {
        if (value == null) node.putNull(key); else node.put(key, money ? value.setScale(2, RoundingMode.HALF_UP).toPlainString() : value.stripTrailingZeros().toPlainString());
    }
}
