package com.yukino.cdn.alert;

import com.yukino.cdn.prediction.PredictionProblem;

import java.util.List;

class FakeAlertSender implements AlertSender {
    int createdChats;
    int createAttempts;
    int sentCards;
    int reminderCards;
    int validateChatCalls;
    int validatedUsers;
    boolean createFails;
    boolean sendFails;
    List<String> invited = List.of();
    String lastChatId = "";
    String lastCard = "";

    @Override
    public String createChat(String name, String description) {
        createAttempts++;
        if (createFails) throw new PredictionProblem(503, "FEISHU_CREATE_FAILED", "建群失败。");
        createdChats++;
        lastChatId = "oc_created";
        return lastChatId;
    }

    @Override
    public void addMembers(String chatId, List<String> openIds) {
        invited = List.copyOf(openIds);
        lastChatId = chatId;
    }

    @Override
    public String sendCard(String chatId, String cardJson, String idempotencyKey) {
        sentCards++;
        lastChatId = chatId;
        lastCard = cardJson;
        if (cardJson.contains("REMINDER")) reminderCards++;
        if (sendFails) throw PredictionProblem.retryable("FEISHU_TIMEOUT", "发送结果未确认。");
        return "om_" + sentCards;
    }

    @Override
    public void validateChat(String chatId) {
        validateChatCalls++;
        lastChatId = chatId;
    }

    @Override
    public void validateUsers(List<String> openIds) {
        validatedUsers += openIds.size();
    }
}
