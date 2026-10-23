package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alert-routes")
public class AlertRouteController {
    private final AlertRouteService service;
    private final AlertRouteActionService actions;

    public AlertRouteController(AlertRouteService service, AlertRouteActionService actions) {
        this.service = service;
        this.actions = actions;
    }

    @GetMapping
    public ArrayNode list() {
        return service.listJson();
    }

    @GetMapping("/{appId}")
    public ObjectNode get(@PathVariable String appId) {
        return service.get(appId);
    }

    @PutMapping("/{appId}")
    public ObjectNode update(@PathVariable String appId, @RequestBody JsonNode body) {
        return service.update(appId, body);
    }

    @PostMapping("/{appId}/validate")
    public ObjectNode validate(@PathVariable String appId) {
        actions.validate(appId);
        return JsonNodeFactory.instance.objectNode().put("appId", appId).put("valid", true);
    }

    @PostMapping("/{appId}/test")
    public ObjectNode test(@PathVariable String appId) {
        actions.test(appId);
        return JsonNodeFactory.instance.objectNode().put("appId", appId).put("sent", true);
    }
}
