package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static com.yukino.cdn.operations.OperationsSupport.problem;

@Component
public class FeishuCallbackVerifier {
    private final AlertSettings settings;
    private final ObjectMapper mapper;

    public FeishuCallbackVerifier(AlertSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public Action verify(JsonNode payload) {
        return verifyPayload(payload, true);
    }

    public Action verify(String rawBody, String timestamp, String nonce, String signature) {
        JsonNode payload;
        boolean signatureVerified = false;
        if (!settings.encryptKey().isBlank()) {
            if (blank(timestamp) || blank(nonce) || blank(signature))
                throw problem(401, "CARD_CALLBACK_UNAUTHORIZED", "飞书卡片回调签名缺失。");
            String expected = hex(sha256((timestamp + nonce + settings.encryptKey()
                    + rawBody).getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.toLowerCase().getBytes(StandardCharsets.UTF_8)))
                throw problem(401, "CARD_CALLBACK_UNAUTHORIZED", "飞书卡片回调签名无效。");
            signatureVerified = true;
            payload = parse(decryptIfNeeded(rawBody));
        } else {
            payload = parse(rawBody);
        }
        return verifyPayload(payload, !signatureVerified);
    }

    private Action verifyPayload(JsonNode payload, boolean requireToken) {
        if (payload == null || !payload.isObject())
            throw problem(422, "INVALID_CARD_CALLBACK", "卡片回调须为 JSON 对象。");
        JsonNode event = payload.path("event").isObject() ? payload.path("event") : payload;
        if (requireToken) {
            String token = payload.path("header").path("token").asText(payload.path("token").asText(""));
            if (!settings.callbackMatches(token))
                throw problem(401, "CARD_CALLBACK_UNAUTHORIZED", "飞书卡片回调验证失败。");
        }
        String type = payload.path("header").path("event_type").asText(payload.path("type").asText(""));
        if (!"card.action.trigger".equals(type))
            throw problem(422, "INVALID_CARD_CALLBACK", "非卡片操作事件。");
        String operatorId = event.path("operator_id").asText(
                event.path("operator").path("operator_id").asText(
                        event.path("operator").path("open_id").asText("")));
        String tag = event.path("action_tag").asText(event.path("action").path("tag").asText(""));
        if (!"button".equals(tag))
            throw problem(422, "INVALID_CARD_ACTION", "仅支持按钮操作。");
        JsonNode rawValue = event.path("action_value");
        if (rawValue.isMissingNode()) rawValue = event.path("action").path("value");
        JsonNode value = rawValue;
        if (rawValue.isTextual()) {
            try { value = mapper.readTree(rawValue.asText()); }
            catch (Exception invalid) { throw problem(422, "INVALID_CARD_ACTION", "按钮动作值无效。"); }
        }
        String action = value.path("action").asText("");
        String incidentId = value.path("incidentId").asText("");
        if (action.isBlank() || incidentId.isBlank() || !value.path("version").isIntegralNumber()
                || !value.path("version").canConvertToInt())
            throw problem(422, "INVALID_CARD_ACTION", "按钮动作缺少事故、操作或版本。");
        return new Action(operatorId, action, incidentId, value.path("version").asInt());
    }

    private JsonNode parse(String rawBody) {
        try { return mapper.readTree(rawBody); }
        catch (Exception invalid) { throw problem(422, "INVALID_CARD_CALLBACK", "卡片回调不是合法 JSON。"); }
    }

    private String decryptIfNeeded(String rawBody) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            String encrypted = root.path("encrypt").asText("");
            if (encrypted.isBlank()) return rawBody;
            return decrypt(encrypted, settings.encryptKey());
        } catch (Exception invalid) {
            throw problem(422, "INVALID_CARD_CALLBACK", "飞书加密回调无法解析。");
        }
    }

    private static String decrypt(String encrypted, String key) throws Exception {
        byte[] keyBytes = sha256(key.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        if (decoded.length <= 16 || (decoded.length - 16) % 16 != 0)
            throw new IllegalArgumentException("invalid encrypted payload");
        Cipher cipher = Cipher.getInstance("AES/CBC/NOPADDING");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(Arrays.copyOfRange(decoded, 0, 16)));
        byte[] plain = cipher.doFinal(Arrays.copyOfRange(decoded, 16, decoded.length));
        int end = plain.length;
        while (end > 0 && plain[end - 1] <= 16) end--;
        return new String(plain, 0, end, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Action(String operatorId, String action, String incidentId, int version) { }
}
