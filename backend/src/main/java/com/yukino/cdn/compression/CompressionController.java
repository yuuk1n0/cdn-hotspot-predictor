package com.yukino.cdn.compression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/compressions")
public class CompressionController {
    private final CompressionRegistrationService service;

    public CompressionController(CompressionRegistrationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ObjectNode> create(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) {
        var accepted = service.create(body, key);
        return ResponseEntity.status(accepted.repeated() ? 200 : 201).body(accepted.record());
    }

    @PutMapping("/{id}")
    public ObjectNode update(@PathVariable String id, @RequestBody JsonNode body) { return service.update(id, body); }

    @GetMapping
    public ObjectNode list(@RequestParam String appId, @RequestParam(required = false) String runId, @RequestParam(required = false) String fileKey,
                           @RequestParam(required = false) String evidenceSource, @RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.list(appId, runId, fileKey, evidenceSource, status, page, pageSize);
    }
}
