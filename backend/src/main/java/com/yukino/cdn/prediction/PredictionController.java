package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PredictionController {
    private final PredictionService service;

    public PredictionController(PredictionService service) { this.service = service; }

    @PostMapping("/predictions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PredictionService.Accepted submit(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        return service.submit(PredictionRequest.fromJson(body), key);
    }

    @GetMapping("/tasks/{taskId}")
    public PredictionTask.View task(@PathVariable String taskId) { return service.get(taskId); }
}
