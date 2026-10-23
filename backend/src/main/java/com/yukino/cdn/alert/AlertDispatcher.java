package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static com.yukino.cdn.operations.OperationsSupport.hash;

@Service
public class AlertDispatcher {
    private final AlertIncidentRepository incidents;
    private final AlertDeliveryRepository deliveries;
    private final AlertRouteService routes;
    private final AlertSender sender;
    private final AlertCardRenderer cards;
    private final Clock clock;

    @Autowired
    public AlertDispatcher(AlertIncidentRepository incidents, AlertDeliveryRepository deliveries,
                           AlertRouteService routes, AlertSender sender, AlertCardRenderer cards) {
        this(incidents, deliveries, routes, sender, cards, Clock.systemUTC());
    }

    AlertDispatcher(AlertIncidentRepository incidents, AlertDeliveryRepository deliveries,
                    AlertRouteService routes, AlertSender sender, AlertCardRenderer cards, Clock clock) {
        this.incidents = incidents;
        this.deliveries = deliveries;
        this.routes = routes;
        this.sender = sender;
        this.cards = cards;
        this.clock = clock;
    }

    public void open(ObjectNode incident, ObjectNode route) {
        if (!routable(route)) {
            incident.put("routingStatus", "ROUTING_FAILED").put("routingError", "ALERT_ROUTE_UNAVAILABLE");
            incidents.update(incident, incident.path("version").asInt());
            return;
        }
        String chatId = incident.path("chatId").asText("");
        if (chatId.isBlank()) chatId = selectChat(incident, route);
        if (chatId.isBlank()) {
            incident.put("routingStatus", "ROUTING_FAILED")
                    .put("routingError", incident.path("routingError").asText("CHAT_UNAVAILABLE"));
            incidents.update(incident, incident.path("version").asInt());
            return;
        }
        deliver(incident, route, "OPEN", chatId);
    }

    public void update(ObjectNode incident) {
        ObjectNode route = routes.get(incident.path("appId").asText());
        String chatId = usableChat(incident, route);
        if (chatId.isBlank()) {
            incident.put("routingStatus", "ROUTING_FAILED").put("routingError", "CHAT_UNAVAILABLE");
            incidents.update(incident, incident.path("version").asInt());
            return;
        }
        deliver(incident, route, "UPDATE", chatId);
    }

    public void recover(ObjectNode incident) {
        ObjectNode route = routes.get(incident.path("appId").asText());
        String chatId = usableChat(incident, route);
        if (chatId.isBlank()) {
            incident.put("routingStatus", "ROUTING_FAILED").put("routingError", "CHAT_UNAVAILABLE");
            incidents.update(incident, incident.path("version").asInt());
            return;
        }
        deliver(incident, route, "RECOVERY", chatId);
    }

    public void reminder(ObjectNode incident, ObjectNode route) {
        String chatId = usableChat(incident, route);
        if (chatId.isBlank()) {
            incident.put("routingStatus", "ROUTING_FAILED").put("routingError", "CHAT_UNAVAILABLE");
            incidents.update(incident, incident.path("version").asInt());
            return;
        }
        deliver(incident, route, "REMINDER", chatId);
    }

    private String selectChat(ObjectNode incident, ObjectNode route) {
        String fixed = route.path("fixedChatId").asText("");
        if (!"P1".equals(incident.path("severity").asText())
                || !route.path("p1AutoCreateGroup").asBoolean(true))
            return useFixed(incident, fixed);

        List<String> members = ids(route.path("ownerOpenIds"));
        members.addAll(ids(route.path("backupOpenIds")));
        try {
            String chatId = sender.createChat(groupName(incident, route), "线上事故自动创建");
            deliveries.record(incident, "GROUP_CREATE", chatId, null, "SENT", null, clock.instant());
            setChat(incident, "INCIDENT_GROUP", chatId);
            try {
                sender.addMembers(chatId, members);
                deliveries.record(incident, "MEMBER_INVITE", chatId, null, "SENT", null, clock.instant());
            } catch (RuntimeException memberFailure) {
                deliveries.record(incident, "MEMBER_INVITE", chatId, null, deliveryStatus(memberFailure),
                        errorCode(memberFailure), clock.instant());
                incident.put("routingError", errorCode(memberFailure));
            }
            return chatId;
        } catch (RuntimeException createFailure) {
            deliveries.record(incident, "GROUP_CREATE", fixed, null, deliveryStatus(createFailure),
                    errorCode(createFailure), clock.instant());
            incident.put("routingError", "GROUP_CREATE_FAILED");
            return useFixed(incident, fixed);
        }
    }

    private String useFixed(ObjectNode incident, String fixed) {
        if (fixed == null || fixed.isBlank()) return "";
        setChat(incident, "FIXED_GROUP", fixed);
        return fixed;
    }

    private void deliver(ObjectNode incident, ObjectNode route, String phase, String chatId) {
        try {
            String card = cards.render(incident, route, phase);
            String idempotency = hash(incident.path("incidentId").asText() + "|" + phase
                    + "|" + incident.path("version").asInt()).substring(0, 32);
            String messageId = sender.sendCard(chatId, card, idempotency);
            deliveries.record(incident, phase, chatId, messageId, "SENT", null, clock.instant());
            incident.put("routingStatus", "ROUTED").put("openingMessageId",
                    "OPEN".equals(phase) ? messageId : incident.path("openingMessageId").asText(""));
            incidents.update(incident, incident.path("version").asInt());
        } catch (RuntimeException failure) {
            String code = errorCode(failure);
            deliveries.record(incident, phase, chatId, null, deliveryStatus(failure), code, clock.instant());
            incident.put("routingStatus", "ROUTING_FAILED").put("routingError", code);
            incidents.update(incident, incident.path("version").asInt());
        }
    }

    private static boolean routable(JsonNode route) {
        return route.path("alertsEnabled").asBoolean()
                && !route.path("ownerOpenIds").isEmpty()
                && !route.path("fixedChatId").asText().isBlank();
    }

    private static String usableChat(ObjectNode incident, ObjectNode route) {
        String chatId = incident.path("chatId").asText("");
        if (!chatId.isBlank()) return chatId;
        if (!route.path("fixedChatId").asText().isBlank()) {
            setChat(incident, "FIXED_GROUP", route.path("fixedChatId").asText());
            return route.path("fixedChatId").asText();
        }
        return "";
    }

    private static void setChat(ObjectNode incident, String mode, String chatId) {
        incident.put("chatMode", mode).put("chatId", chatId);
    }

    private static List<String> ids(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (var value : values) result.add(value.asText());
        return result;
    }

    private static String groupName(ObjectNode incident, ObjectNode route) {
        String value = "[P1] " + incident.path("appId").asText() + "-"
                + route.path("businessName").asText(incident.path("appId").asText())
                + "-" + incident.path("incidentType").asText();
        return value.length() <= 60 ? value : value.substring(0, 60);
    }

    private static String errorCode(RuntimeException error) {
        return error instanceof PredictionProblem problem ? problem.code() : "FEISHU_DISPATCH_FAILED";
    }

    private static String deliveryStatus(RuntimeException error) {
        String code = errorCode(error);
        return code.contains("TIMEOUT") || code.contains("INTERRUPTED") || code.contains("NETWORK")
                ? "UNKNOWN" : "FAILED";
    }

    private static PredictionProblem problem(int status, String code, String message) {
        return new PredictionProblem(status, code, message);
    }
}
