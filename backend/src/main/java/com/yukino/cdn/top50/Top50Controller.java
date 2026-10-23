package com.yukino.cdn.top50;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/top50")
public class Top50Controller {

    private final Top50Service service;

    public Top50Controller(Top50Service service) {
        this.service = service;
    }

    @GetMapping
    public Top50Page search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String modelVersion,
            @RequestParam(required = false) String compressionStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        return service.search(new Top50Query(
                startDate,
                endDate,
                appId,
                granularity,
                modelVersion,
                compressionStatus,
                page,
                pageSize
        ));
    }

    @GetMapping("/options")
    public Top50Options options() {
        return service.options();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "INVALID_REQUEST",
                "message", exception.getMessage()
        ));
    }
}
