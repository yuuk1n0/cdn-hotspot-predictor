package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.yukino.cdn.operations.OperationsSupport.problem;

@RestController
@RequestMapping("/api/v1/feishu/card-actions")
public class FeishuCardActionController {
    private final AlertIncidentService incidents;
    private final FeishuCallbackVerifier verifier;

    public FeishuCardActionController(AlertIncidentService incidents, FeishuCallbackVerifier verifier) {
        this.incidents = incidents;
        this.verifier = verifier;
    }

    @PostMapping
    public ObjectNode handle(@RequestBody String rawBody,
                             @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
                             @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
                             @RequestHeader(value = "X-Lark-Signature", required = false) String signature) {
        FeishuCallbackVerifier.Action action = verifier.verify(rawBody, timestamp, nonce, signature);
        ObjectNode incident;
        switch (action.action()) {
            case "ACK" -> incident = incidents.ack(action.incidentId(), action.operatorId(), action.version());
            case "PROCESSING" -> incident = incidents.processing(action.incidentId(), action.operatorId(), action.version());
            case "RECOVER" -> incident = incidents.recoverAsUser(action.incidentId(), action.operatorId());
            case "DETAIL" -> incident = incidents.get(action.incidentId());
            default -> throw problem(422, "INVALID_CARD_ACTION", "不支持的卡片操作。");
        }
        return JsonNodeFactory.instance.objectNode().put("code", 0)
                .put("incidentId", incident.path("incidentId").asText())
                .put("status", incident.path("status").asText());
    }
}
