package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class TrafficMetricsService {
    private final PredictionService predictions;
    private final TrafficMetricsRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TrafficMetricsService(PredictionService predictions, TrafficMetricsRepository repository,
                                 ObjectMapper mapper, Clock clock) {
        this.predictions = predictions;
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    public ObjectNode forRun(String runId) {
        JsonNode snapshot = predictions.result(runId);
        String appId = snapshot.path("appId").asText();
        String datasetSource = snapshot.path("datasetSource").asText();
        String predictionSource = snapshot.path("predictionSource").asText();
        LocalDate start = LocalDate.parse(snapshot.path("predictionStartDate").asText());
        LocalDate end = LocalDate.parse(snapshot.path("predictionEndDate").asText());
        int horizonDays = snapshot.path("horizonDays").asInt();
        int expectedDays = Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
        if (expectedDays != horizonDays) throw new PredictionProblem(422, "INVALID_RESULT", "预测窗口与跨度不一致。");

        ObjectNode result = mapper.createObjectNode();
        result.put("appId", appId).put("runId", runId).put("windowStart", start.toString())
                .put("windowEnd", end.toString()).put("horizonDays", horizonDays)
                .put("datasetSource", datasetSource).put("predictionSource", predictionSource)
                .put("generatedAt", clock.instant().toString());

        var latest = repository.latestObservedDate(appId, datasetSource);
        var total = repository.total(appId, start, end, datasetSource);
        result.set("actualPredictedTop50", predictedTop50(snapshot, appId, datasetSource, predictionSource,
                start, end, horizonDays, latest.orElse(null), total));
        result.set("actualTop50", actualTop50(appId, datasetSource, start, end, horizonDays,
                latest.orElse(null), total));
        result.put("metricContract", "ACTUAL_BYTES_ONLY_WHEN_WINDOW_MATURED");
        return result;
    }

    private ObjectNode predictedTop50(JsonNode snapshot, String appId, String datasetSource,
                                      String predictionSource, LocalDate start, LocalDate end, int horizonDays,
                                      LocalDate latestObserved, TrafficMetricsRepository.TotalWindow total) {
        var metric = metric("ACTUAL_PREDICTED_TOP50");
        if (!"MODEL".equals(predictionSource)) return unavailable(metric, "MOCK_RESULT");
        if (!"REAL".equals(datasetSource)) return unavailable(metric, "DATA_SOURCE_NOT_REAL");
        String maturity = maturity(start, end, horizonDays, latestObserved, total);
        if (maturity != null) return unavailable(metric, maturity);

        Set<String> hashes = new LinkedHashSet<>();
        for (JsonNode item : snapshot.path("items")) hashes.add(hash(item.path("fileKey").asText()));
        var actual = repository.actualByHashes(appId, start, end, datasetSource, hashes);
        if (actual.size() != hashes.size()) return unavailable(metric, "TOP50_ACTUAL_MISSING");
        BigDecimal numerator = actual.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return complete(metric, numerator, total.totalBytes(), "MYSQL_FILE_DAILY_OBSERVATION", "MYSQL_APP_DAILY_TOTAL");
    }

    private ObjectNode actualTop50(String appId, String datasetSource, LocalDate start, LocalDate end,
                                   int horizonDays, LocalDate latestObserved,
                                   TrafficMetricsRepository.TotalWindow total) {
        var metric = metric("ACTUAL_TOP50_CONCENTRATION");
        if (!"REAL".equals(datasetSource)) return unavailable(metric, "DATA_SOURCE_NOT_REAL");
        String maturity = maturity(start, end, horizonDays, latestObserved, total);
        if (maturity != null) return unavailable(metric, maturity);
        BigDecimal numerator = repository.topActual(appId, start, end, datasetSource, 50).stream()
                .map(TrafficMetricsRepository.ActualFile::actualBytes)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return complete(metric, numerator, total.totalBytes(), "MYSQL_FILE_DAILY_OBSERVATION", "MYSQL_APP_DAILY_TOTAL");
    }

    private String maturity(LocalDate start, LocalDate end, int horizonDays, LocalDate latestObserved,
                            TrafficMetricsRepository.TotalWindow total) {
        if (latestObserved == null || latestObserved.isBefore(end)) return "TOP50_ACTUAL_NOT_MATURED";
        if (total.observedDays() < horizonDays) return "TOTAL_TRAFFIC_MISSING";
        if (total.totalBytes() == null || total.totalBytes().signum() <= 0) return "DENOMINATOR_INCONSISTENT";
        return null;
    }

    private ObjectNode metric(String basis) {
        return mapper.createObjectNode().put("metricBasis", basis).putNull("value")
                .putNull("numeratorBytes").putNull("denominatorBytes")
                .put("numeratorSource", "MYSQL_FILE_DAILY_OBSERVATION").put("denominatorSource", "MYSQL_APP_DAILY_TOTAL")
                .putNull("reasonCode");
    }

    private ObjectNode unavailable(ObjectNode metric, String reasonCode) {
        return metric.put("reasonCode", reasonCode);
    }

    private ObjectNode complete(ObjectNode metric, BigDecimal numerator, BigDecimal denominator,
                                String numeratorSource, String denominatorSource) {
        if (numerator.compareTo(denominator) > 0) return unavailable(metric, "DENOMINATOR_INCONSISTENT");
        BigDecimal percent = numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 6, RoundingMode.HALF_UP).stripTrailingZeros();
        return metric.put("value", percent).put("numeratorBytes", numerator.toPlainString())
                .put("denominatorBytes", denominator.toPlainString())
                .put("numeratorSource", numeratorSource).put("denominatorSource", denominatorSource)
                .putNull("reasonCode");
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
