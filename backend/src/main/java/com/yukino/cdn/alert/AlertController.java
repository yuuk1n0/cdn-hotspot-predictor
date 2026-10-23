package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertIncidentService service;
    private final AlertSettings settings;

    public AlertController(AlertIncidentService service, AlertSettings settings) {
        this.service = service;
        this.settings = settings;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode ingest(@RequestHeader("X-Alert-Token") String token, @RequestBody JsonNode body) {
        settings.verifyIngestToken(token);
        return service.ingest(body);
    }

    @GetMapping
    public ObjectNode list(@RequestParam String appId,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) String severity,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int pageSize) {
        return service.list(appId, status, severity, page, pageSize);
    }

    @GetMapping("/{incidentId}")
    public ObjectNode get(@PathVariable String incidentId) {
        return service.get(incidentId);
    }
}
