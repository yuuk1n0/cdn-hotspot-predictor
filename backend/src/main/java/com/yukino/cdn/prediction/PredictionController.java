package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PredictionController {
    private final PredictionService service;
    private final TrafficMetricsService metrics;

    public PredictionController(PredictionService service) { this(service, null); }

    @Autowired public PredictionController(PredictionService service, TrafficMetricsService metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping("/predictions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PredictionService.Accepted submit(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return service.submit(PredictionRequest.fromJson(body), key);
    }

    @GetMapping("/tasks/{taskId}")
    public PredictionTask.View task(@PathVariable String taskId) { return service.get(taskId); }

    @GetMapping("/predictions/{runId}")
    public JsonNode result(@PathVariable String runId) { return service.result(runId); }

    @GetMapping("/predictions/{runId}/metrics")
    public ObjectNode metrics(@PathVariable String runId) {
        if (metrics == null) throw new PredictionProblem(503, "METRICS_UNAVAILABLE", "流量占比服务未配置。");
        return metrics.forRun(runId);
    }
}
