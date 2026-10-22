package com.yukino.cdn.operations;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final OperationsService service;

    public OperationsController(OperationsService service) { this.service = service; }

    @GetMapping("/runs")
    public ObjectNode runs(@RequestParam String appId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
                           @RequestParam(defaultValue = "1") int horizonDays) {
        return service.runList(appId, businessDate, horizonDays);
    }

    @GetMapping("/latest")
    public ObjectNode latest(@RequestParam String appId,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
                             @RequestParam(defaultValue = "1") int horizonDays) {
        return service.latestRun(appId, businessDate, horizonDays);
    }

    @GetMapping("/effects")
    public ObjectNode effects(@RequestParam String appId, @RequestParam(defaultValue = "REAL") String evidenceSource) {
        return service.effects(appId, evidenceSource);
    }

    @GetMapping("/pending")
    public ObjectNode pending(@RequestParam String appId, @RequestParam String runId,
                              @RequestParam(defaultValue = "REAL") String evidenceSource) {
        return service.pending(appId, runId, evidenceSource);
    }

    @GetMapping("/metrics")
    public ObjectNode metrics(@RequestParam String appId, @RequestParam(defaultValue = "1") int horizonDays) {
        return service.modelMetrics(appId, horizonDays);
    }
}
