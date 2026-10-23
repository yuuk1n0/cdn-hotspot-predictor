package com.yukino.cdn.alert;

import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AlertSettings {
    private final String appId;
    private final String appSecret;
    private final String verificationToken;
    private final String ingestToken;
    private final String encryptKey;
    private final URI baseUri;

    public AlertSettings(String appId, String appSecret, String verificationToken,
                         String baseUrl, boolean testMode) {
        this(appId, appSecret, verificationToken, "", "", baseUrl, testMode);
    }

    public AlertSettings(String appId, String appSecret, String verificationToken, String ingestToken,
                         String baseUrl, boolean testMode) {
        this(appId, appSecret, verificationToken, ingestToken, "", baseUrl, testMode);
    }

    @Autowired
    public AlertSettings(@Value("${FEISHU_APP_ID:}") String appId,
                         @Value("${FEISHU_APP_SECRET:}") String appSecret,
                         @Value("${FEISHU_VERIFICATION_TOKEN:}") String verificationToken,
                         @Value("${ALERT_INGEST_TOKEN:}") String ingestToken,
                         @Value("${FEISHU_ENCRYPT_KEY:}") String encryptKey,
                         @Value("${FEISHU_API_BASE_URL:https://open.feishu.cn}") String baseUrl,
                         @Value("${FEISHU_TEST_MODE:false}") boolean testMode) {
        this.appId = safe(appId, 128);
        this.appSecret = safe(appSecret, 512);
        this.verificationToken = safe(verificationToken, 512);
        this.ingestToken = safe(ingestToken, 512);
        this.encryptKey = safe(encryptKey, 512);
        if (this.appId.isBlank() != this.appSecret.isBlank())
            throw new IllegalArgumentException("飞书应用 ID 和密钥必须同时配置。");
        this.baseUri = validateBaseUri(baseUrl, testMode);
    }

    void requireAppCredentials() {
        if (appId.isBlank()) throw problem(503, "FEISHU_APP_NOT_CONFIGURED", "飞书企业应用机器人未配置。");
    }

    public void verifyIngestToken(String supplied) {
        if (ingestToken.isBlank())
            throw problem(503, "ALERT_INGEST_NOT_CONFIGURED", "告警入口令牌未配置。");
        if (!constantTimeEquals(ingestToken, supplied))
            throw problem(401, "ALERT_INGEST_UNAUTHORIZED", "告警入口鉴权失败。");
    }

    public boolean callbackMatches(String supplied) {
        return !verificationToken.isBlank() && constantTimeEquals(verificationToken, supplied);
    }

    String appId() { return appId; }
    String appSecret() { return appSecret; }
    String encryptKey() { return encryptKey; }
    URI baseUri() { return baseUri; }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String value, int maxLength) {
        String result = value == null ? "" : value.strip();
        if (result.length() > maxLength || result.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("飞书告警配置包含非法字符。");
        return result;
    }

    private static URI validateBaseUri(String value, boolean testMode) {
        String text = value == null ? "" : value.strip();
        URI uri;
        try { uri = URI.create(text); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("飞书 API 地址无效。"); }
        boolean clean = uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath()));
        boolean official = clean && "https".equalsIgnoreCase(uri.getScheme())
                && "open.feishu.cn".equalsIgnoreCase(uri.getHost()) && uri.getPort() == -1;
        boolean loopback = clean && testMode && "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())
                || "::1".equals(uri.getHost()));
        if (!official && !loopback) throw new IllegalArgumentException("飞书 API 地址无效。");
        String normalized = uri.toString().replaceAll("/+$", "");
        return URI.create(normalized);
    }

    private static PredictionProblem problem(int status, String code, String message) {
        return new PredictionProblem(status, code, message);
    }

    @Override
    public String toString() {
        return "AlertSettings{configured=" + !appId.isBlank()
                + ", baseUrl=" + baseUri
                + ", callbackConfigured=" + !verificationToken.isBlank()
                + ", ingestConfigured=" + !ingestToken.isBlank()
                + ", encryptConfigured=" + !encryptKey.isBlank() + "}";
    }
}
