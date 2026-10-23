package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.yukino.cdn.operations.OperationsSupport.problem;

@Service
public class AlertRouteActionService {
    private final AlertRouteService routes;
    private final AlertSender sender;
    private final AlertCardRenderer cards;

    @Autowired
    public AlertRouteActionService(AlertRouteService routes, AlertSender sender, ObjectMapper mapper) {
        this(routes, sender, new AlertCardRenderer(mapper));
    }

    AlertRouteActionService(AlertRouteService routes, AlertSender sender) {
        this(routes, sender, new AlertCardRenderer(new ObjectMapper()));
    }

    private AlertRouteActionService(AlertRouteService routes, AlertSender sender, AlertCardRenderer cards) {
        this.routes = routes;
        this.sender = sender;
        this.cards = cards;
    }

    public void validate(String appId) {
        ObjectNode route = routes.get(appId);
        String fixed = route.path("fixedChatId").asText("");
        String test = route.path("testChatId").asText("");
        if (fixed.isBlank() && test.isBlank())
            throw problem(422, "ALERT_CHAT_REQUIRED", "至少配置一个固定群或测试群。");
        if (!fixed.isBlank()) sender.validateChat(fixed);
        if (!test.isBlank()) sender.validateChat(test);
        List<String> users = new ArrayList<>();
        route.path("ownerOpenIds").forEach(value -> users.add(value.asText()));
        route.path("backupOpenIds").forEach(value -> users.add(value.asText()));
        sender.validateUsers(users);
    }

    public void test(String appId) {
        ObjectNode route = routes.get(appId);
        String testChatId = route.path("testChatId").asText("");
        if (testChatId.isBlank()) throw problem(422, "TEST_CHAT_REQUIRED", "未配置测试群。");
        sender.validateChat(testChatId);
        sender.sendCard(testChatId,
                cards.testCard(route.path("businessName").asText(appId), testChatId),
                "route-test-" + appId);
    }
}
