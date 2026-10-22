package com.yukino.cdn.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsService;
import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/app-configs/{appId}")
public class AppConfigController {
    private final OperationsService operations;

    public AppConfigController(OperationsService operations) { this.operations = operations; }

    @GetMapping
    public ObjectNode config(@PathVariable String appId) { return operations.config(appId); }

    @PutMapping
    public ObjectNode update(@PathVariable String appId, @RequestBody JsonNode body) {
        return operations.updateConfig(appId, body);
    }

    @PostMapping("/model-suggestions")
    public ObjectNode suggest(@PathVariable String appId, @RequestBody JsonNode body) {
        if (!body.isObject() || !body.path("horizonDays").isIntegralNumber()
                || !body.path("horizonDays").canConvertToInt()) throw invalid();
        JsonNode report = body.path("backtestId");
        if (!report.isMissingNode() && (!report.isTextual() || report.asText().isBlank() || report.asText().length() > 128))
            throw invalid();
        return operations.suggestion(appId, body.path("horizonDays").intValue(), report.isTextual() ? report.asText() : null);
    }

    @PostMapping("/model-reviews")
    public ObjectNode review(@PathVariable String appId, @RequestBody(required = false) JsonNode body) {
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.put("appId", appId);
        if (body != null && !body.isObject()) throw invalid();
        int requested = body != null && body.has("horizonDays") ? body.path("horizonDays").asInt() : 0;
        if (requested != 0 && requested != 1 && requested != 7) throw invalid();
        var reviews = result.putArray("reviews");
        for (int horizon : requested == 0 ? new int[]{1, 7} : new int[]{requested}) {
            try {
                reviews.add(operations.suggestion(appId, horizon, null));
            } catch (com.yukino.cdn.prediction.PredictionProblem problem) {
                reviews.addObject().put("appId", appId).put("horizonDays", horizon)
                        .put("errorCode", problem.code()).put("errorMessage", problem.getMessage());
            }
        }
        return result;
    }

    @PostMapping("/model-switches")
    public ObjectNode switchModel(@PathVariable String appId, @RequestBody JsonNode body) {
        if (!body.isObject()) throw invalid();
        return operations.switchModel(appId, body);
    }

    private static PredictionProblem invalid() {
        return new PredictionProblem(422, "INVALID_CONFIG", "App配置请求格式不合法。");
    }
}
