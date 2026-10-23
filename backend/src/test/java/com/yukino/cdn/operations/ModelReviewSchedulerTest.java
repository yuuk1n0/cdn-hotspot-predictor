package com.yukino.cdn.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelReviewSchedulerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void enabledAppsAutoReviewBothHorizonsAndOneFailureDoesNotBlockAnotherApp() {
        var operations = new FakeOperations();
        var scheduler = new ModelReviewScheduler(operations);

        scheduler.reviewConfiguredApps();

        assertEquals(List.of("10066:1", "10066:7", "10350:1", "10350:7"), operations.calls);
    }

    private final class FakeOperations implements OperationsService {
        private final List<String> calls = new ArrayList<>();

        @Override public List<String> configuredApps() { return List.of("disabled", "10066", "10350"); }
        @Override public ObjectNode config(String appId) {
            return mapper.createObjectNode().put("appId", appId).put("modelReviewEnabled", !"disabled".equals(appId));
        }
        @Override public ObjectNode reviewAndAutoSwitch(String appId, int horizonDays) {
            calls.add(appId + ":" + horizonDays);
            if ("10066".equals(appId) && horizonDays == 1)
                throw new PredictionProblem(503, "MODEL_REVIEW_UNAVAILABLE", "fixture");
            return mapper.createObjectNode().put("autoSwitchStatus", "NOT_RECOMMENDED");
        }
        @Override public ObjectNode updateConfig(String appId, JsonNode body) { throw new UnsupportedOperationException(); }
        @Override public ObjectNode run(String appId, String runId, LocalDate businessDate, int horizonDays) { throw new UnsupportedOperationException(); }
        @Override public ObjectNode pending(String appId, String runId, String evidenceSource) { throw new UnsupportedOperationException(); }
        @Override public ObjectNode overdue(String appId, String runId, String evidenceSource) { throw new UnsupportedOperationException(); }
        @Override public ObjectNode evidence(String tool, JsonNode context, JsonNode arguments) { throw new UnsupportedOperationException(); }
        @Override public ObjectNode suggestion(String appId, int horizonDays, String backtestId) { throw new UnsupportedOperationException(); }
    }
}
