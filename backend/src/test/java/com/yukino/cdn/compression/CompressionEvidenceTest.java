package com.yukino.cdn.compression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class CompressionEvidenceTest {
    final ObjectMapper json = new ObjectMapper();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
    ObjectNode record() throws Exception {
        return (ObjectNode) json.readTree("""
                {"appId":"10066","evidenceSource":"TEST","status":"COMPRESSED","handler":"tester",
                 "originalSizeBytes":"100000000","compressedSizeBytes":"60000000","compressedFileKey":"small.mp4",
                 "compressedAt":"2026-10-06T02:00:00Z","quality":{"metric":"VMAF","value":92,"evidenceNote":"fixture report"}}
                """);
    }
    ObjectNode observation() throws Exception {
        return (ObjectNode) json.readTree("""
                {"appId":"10066","evidenceSource":"TEST","windowDays":7,"startDate":"2026-10-07","endDate":"2026-10-13",
                 "baselineTrafficBytes":"1000000000000","routedShare":0.8,"actualAccessCount":10000,"accessible":true,
                 "transcodingCostYuan":"1.00","storageCostYuan":"0.40","evidenceNote":"fixture observed total equivalent bytes",
                 "qualityEvidenceNote":"same version quality"}
                """);
    }
    @Test void completedSizeAndQualityDoNotPretendToProveActualEffect() throws Exception {
        var result = CompressionEvidence.complete(record(), clock);
        assertEquals(0.4, result.path("compressionRatio").doubleValue(), 1e-12);
        assertTrue(result.path("qualityPassed").booleanValue());
        assertEquals("PENDING_OBSERVATION", result.path("effectiveCompressionStatus").asText());
        var unknown = record(); unknown.putObject("quality").put("metric", "UNKNOWN").put("evidenceNote", "not measured");
        assertTrue(CompressionEvidence.complete(unknown, clock).path("qualityPassed").isNull());
        var bigger = record().put("compressedSizeBytes", "100000001");
        assertThrows(PredictionProblem.class, () -> CompressionEvidence.complete(bigger, clock));
    }
    @Test void matureObservationUsesTheDocumentedDecimalFormulaAndNeverCallsItBillSaving() throws Exception {
        var result = CompressionEvidence.observe(CompressionEvidence.complete(record(), clock), observation(), clock);
        assertEquals("320000000000", result.path("estimatedSavedBytes").asText());
        assertEquals("6.40", result.path("estimatedCdnSavingYuan").asText());
        assertEquals("5.00", result.path("estimatedNetBenefitYuan").asText());
        assertEquals("PASS", result.path("effectiveCompressionStatus").asText());
        assertFalse(result.path("isRealizedBillSaving").booleanValue());
    }
    @Test void missingCostIsUnknownNotZeroAndUnmaturedOrCrossAppEvidenceIsRejected() throws Exception {
        var input = observation(); input.remove("storageCostYuan");
        var result = CompressionEvidence.observe(CompressionEvidence.complete(record(), clock), input, clock);
        assertTrue(result.path("estimatedNetBenefitYuan").isNull());
        assertEquals("UNKNOWN", result.path("effectiveCompressionStatus").asText());
        var processing = record().put("status", "PROCESSING");
        assertThrows(PredictionProblem.class, () -> CompressionEvidence.observe(processing, input, clock));
        var futureClock = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC);
        assertEquals("WINDOW_NOT_MATURE", assertThrows(PredictionProblem.class, () -> CompressionEvidence.observe(CompressionEvidence.complete(record(), clock), observation(), futureClock)).code());
        var crossApp = observation().put("appId", "other");
        assertThrows(PredictionProblem.class, () -> CompressionEvidence.observe(CompressionEvidence.complete(record(), clock), crossApp, clock));
    }
}
