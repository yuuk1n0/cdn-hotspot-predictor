package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class FeishuOpenApiClient implements AlertSender {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private final AlertSettings settings;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;
    private String tenantToken;
    private Instant tokenRefreshAt = Instant.MIN;

    @Autowired
    public FeishuOpenApiClient(AlertSettings settings, ObjectMapper mapper, Clock clock) {
        this(settings, mapper, clock, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    FeishuOpenApiClient(AlertSettings settings, ObjectMapper mapper, Clock clock, HttpClient http) {
        this.settings = settings;
        this.mapper = mapper;
        this.clock = clock;
        this.http = http;
    }

    @Override
    public String createChat(String name, String description) {
        requireText(name, 60, "群名称");
        requireText(description, 100, "群描述");
        var body = mapper.createObjectNode()
                .put("name", name)
                .put("description", description)
                .put("chat_mode", "group")
                .put("chat_type", "private");
        JsonNode data = authenticated("POST", "/open-apis/im/v1/chats?user_id_type=open_id", body);
        String chatId = data.path("chat_id").asText("");
        if (chatId.isBlank()) throw problem(502, "FEISHU_RESPONSE_INVALID", "飞书建群响应缺少群 ID。");
        return chatId;
    }

    @Override
    public void addMembers(String chatId, List<String> openIds) {
        requireChatId(chatId);
        if (openIds == null || openIds.isEmpty() || openIds.size() > 50)
            throw problem(422, "INVALID_MEMBERS", "群成员须为 1 至 50 个飞书用户 ID。");
        openIds.forEach(FeishuOpenApiClient::requireOpenId);
        var body = mapper.createObjectNode();
        var ids = body.putArray("id_list");
        openIds.forEach(ids::add);
        JsonNode data = authenticated("POST",
                "/open-apis/im/v1/chats/" + chatId + "/members?member_id_type=open_id&succeed_type=1", body);
        if (hasItems(data.path("invalid_id_list")) || hasItems(data.path("not_existed_id_list"))
                || hasItems(data.path("pending_approval_id_list")))
            throw problem(409, "FEISHU_MEMBER_PARTIAL", "部分成员未成功加入飞书群。");
    }

    @Override
    public String sendCard(String chatId, String cardJson, String idempotencyKey) {
        requireChatId(chatId);
        try {
            mapper.readTree(cardJson);
        } catch (Exception invalid) {
            throw problem(422, "INVALID_CARD_JSON", "飞书卡片内容须为合法 JSON。");
        }
        if (idempotencyKey != null && (idempotencyKey.length() > 50
                || idempotencyKey.chars().anyMatch(c -> c < 32 || c == 127)))
            throw problem(422, "INVALID_IDEMPOTENCY_KEY", "飞书幂等键不合法。");
        var body = mapper.createObjectNode()
                .put("receive_id", chatId)
                .put("msg_type", "interactive")
                .put("content", cardJson);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) body.put("uuid", idempotencyKey);
        JsonNode data = authenticated("POST",
                "/open-apis/im/v1/messages?receive_id_type=chat_id", body);
        String messageId = data.path("message_id").asText("");
        if (messageId.isBlank()) throw problem(502, "FEISHU_RESPONSE_INVALID", "飞书消息响应缺少消息 ID。");
        return messageId;
    }

    @Override
    public void validateChat(String chatId) {
        requireChatId(chatId);
        JsonNode data = authenticated("GET",
                "/open-apis/im/v1/chats/" + chatId + "?user_id_type=open_id", null);
        if (!"normal".equals(data.path("chat_status").asText("normal")))
            throw problem(409, "FEISHU_CHAT_UNAVAILABLE", "飞书群不可用。");
    }

    @Override
    public void validateUsers(List<String> openIds) {
        if (openIds == null || openIds.isEmpty()) return;
        openIds.forEach(FeishuOpenApiClient::requireOpenId);
        for (String openId : openIds)
            authenticated("GET", "/open-apis/contact/v3/users/" + openId + "?user_id_type=open_id", null);
    }

    private JsonNode authenticated(String method, String path, JsonNode body) {
        return authenticated(method, path, body, true);
    }

    private JsonNode authenticated(String method, String path, JsonNode body, boolean retryAuth) {
        Response response = request(method, path, body, tenantToken());
        if (response.status() == 401 && retryAuth) {
            invalidateToken();
            return authenticated(method, path, body, false);
        }
        return decode(response);
    }

    private synchronized String tenantToken() {
        settings.requireAppCredentials();
        if (tenantToken != null && clock.instant().isBefore(tokenRefreshAt)) return tenantToken;
        var body = mapper.createObjectNode()
                .put("app_id", settings.appId())
                .put("app_secret", settings.appSecret());
        JsonNode result = decodeRoot(request("POST",
                "/open-apis/auth/v3/tenant_access_token/internal", body, null));
        String token = result.path("tenant_access_token").asText("");
        int expiresIn = result.path("expire").asInt(0);
        if (token.isBlank() || expiresIn <= 0)
            throw problem(502, "FEISHU_AUTH_FAILED", "飞书返回了无效的租户令牌。");
        tenantToken = token;
        tokenRefreshAt = clock.instant().plusSeconds(Math.max(1, expiresIn - 60L));
        return tenantToken;
    }

    private synchronized void invalidateToken() {
        tenantToken = null;
        tokenRefreshAt = Instant.MIN;
    }

    private Response request(String method, String path, JsonNode body, String token) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(settings.baseUri() + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json; charset=utf-8");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json; charset=utf-8");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw problem(503, "FEISHU_REQUEST_INTERRUPTED", "飞书请求被中断，发送结果未确认。");
        } catch (HttpTimeoutException timeout) {
            throw problem(503, "FEISHU_TIMEOUT", "飞书请求超时，发送结果未确认。");
        } catch (IOException | IllegalArgumentException error) {
            throw problem(503, "FEISHU_NETWORK_ERROR", "飞书请求失败，发送结果未确认。");
        }
    }

    private JsonNode decode(Response response) {
        return decodeRoot(response).path("data");
    }

    private JsonNode decodeRoot(Response response) {
        if (response.status() == 429)
            throw PredictionProblem.retryable("FEISHU_RATE_LIMITED", "飞书接口限流，稍后重试。");
        if (response.status() >= 500)
            throw PredictionProblem.retryable("FEISHU_SERVER_ERROR", "飞书服务暂时不可用。");
        JsonNode result;
        try {
            result = mapper.readTree(response.body());
        } catch (Exception invalid) {
            throw problem(502, "FEISHU_RESPONSE_INVALID", "飞书响应不是合法 JSON。");
        }
        if (result == null || !result.isObject())
            throw problem(502, "FEISHU_RESPONSE_INVALID", "飞书响应结构无效。");
        int code = result.path("code").asInt(Integer.MIN_VALUE);
        if (response.status() == 401 || code == 99991663)
            throw problem(401, "FEISHU_TOKEN_EXPIRED", "飞书租户令牌已失效。");
        if (response.status() < 200 || response.status() >= 300)
            throw problem(502, "FEISHU_HTTP_ERROR", "飞书接口返回 HTTP 错误。");
        if (code != 0) throw problem(502, "FEISHU_API_REJECTED", "飞书接口拒绝了请求，错误码 " + code + "。");
        return result;
    }

    private static boolean hasItems(JsonNode value) {
        return value.isArray() && !value.isEmpty();
    }

    private static void requireChatId(String chatId) {
        if (chatId == null || !chatId.matches("[A-Za-z0-9_-]{1,128}"))
            throw problem(422, "INVALID_CHAT_ID", "飞书群 ID 不合法。");
    }

    private static void requireOpenId(String openId) {
        if (openId == null || !openId.matches("[A-Za-z0-9_-]{1,128}"))
            throw problem(422, "INVALID_OPEN_ID", "飞书用户 ID 不合法。");
    }

    private static void requireText(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw problem(422, "INVALID_FEISHU_TEXT", label + "不合法。");
    }

    private static PredictionProblem problem(int status, String code, String message) {
        return new PredictionProblem(status, code, message);
    }

    private record Response(int status, String body) { }
}
