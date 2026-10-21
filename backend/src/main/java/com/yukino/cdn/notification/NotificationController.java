package com.yukino.cdn.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private final NotificationService service;
    private final OperationsService operations;
    public NotificationController(NotificationService service, OperationsService operations) {
        this.service = service; this.operations = operations;
    }
    @GetMapping("/notifications/destinations") public JsonNode destinations() { return service.destinations(); }
    @GetMapping("/notifications/config") public ObjectNode config(@RequestParam String appId) { return operations.config(appId); }
    @PutMapping("/notifications/config") public ObjectNode updateConfig(@RequestParam String appId, @RequestBody JsonNode body) {
        return operations.updateConfig(appId, body);
    }
    @GetMapping("/notifications/preview") public ObjectNode preview(@RequestParam String appId, @RequestParam String runId,
            @RequestParam String type, @RequestParam String purpose, @RequestParam(defaultValue = "REAL") String evidenceSource) {
        return service.preview(appId, runId, type, purpose, evidenceSource);
    }
    @PostMapping("/notifications/send") @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode send(@RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body) { return service.submit(body, key); }
    @GetMapping("/notifications") public ObjectNode list(@RequestParam String appId,
            @RequestParam(required = false) String runId, @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.list(appId, runId, type, page, pageSize);
    }
    @GetMapping("/overdue") public ObjectNode overdue(@RequestParam String appId, @RequestParam String runId,
            @RequestParam(defaultValue = "REAL") String evidenceSource) { return service.overdue(appId, runId, evidenceSource); }
}
