package com.yukino.cdn.history;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {
    private final HistoryService service;
    public HistoryController(HistoryService service) { this.service = service; }
    @GetMapping public JsonNode query(@RequestParam Map<String,String> params) { return service.query(params); }
}
