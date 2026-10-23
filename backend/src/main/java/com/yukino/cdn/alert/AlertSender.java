package com.yukino.cdn.alert;

import java.util.List;

public interface AlertSender {
    String createChat(String name, String description);

    void addMembers(String chatId, List<String> openIds);

    default String sendCard(String chatId, String cardJson) {
        return sendCard(chatId, cardJson, null);
    }

    String sendCard(String chatId, String cardJson, String idempotencyKey);

    void validateChat(String chatId);

    void validateUsers(List<String> openIds);
}
