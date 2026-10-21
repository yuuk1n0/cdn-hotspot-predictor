package com.yukino.cdn.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.math.BigInteger;

/** CStore configuration and the CSV URL-path identity rule confirmed by the user. */
@Component
public final class CStoreSettings {
    final String baseUrl, authHeaderName, authHeaderValue, dataSource;
    final boolean authNoneConfirmed, bytesConfirmed;
    final int qps, concurrency;
    private final JsonNode mappings;

    public CStoreSettings(Environment env, ObjectMapper mapper) {
        baseUrl = env.getProperty("CSTORE_BASE_URL", "http://cstore.seewo.com");
        authHeaderName = env.getProperty("CSTORE_AUTH_HEADER_NAME", "");
        authHeaderValue = env.getProperty("CSTORE_AUTH_HEADER_VALUE", "");
        authNoneConfirmed = Boolean.parseBoolean(env.getProperty("CSTORE_AUTH_NONE_CONFIRMED", "false"));
        // Verified against Content-Length on a live 10066 sample (2026-10-21).
        bytesConfirmed = Boolean.parseBoolean(env.getProperty("CSTORE_SIZE_UNIT_BYTES_CONFIRMED", "true"));
        String mode = env.getProperty("CSTORE_MODE", "LIVE");
        if (!mode.equals("LIVE") && !mode.equals("TEST")) throw new IllegalArgumentException("CSTORE_MODE必须为LIVE或TEST。");
        dataSource = mode.equals("TEST") ? "CSTORE_TEST" : "CSTORE";
        try {
            qps = Integer.parseInt(env.getProperty("CSTORE_QPS", "20"));
            concurrency = Integer.parseInt(env.getProperty("CSTORE_MAX_CONCURRENT", "2"));
            if (qps < 1 || qps > 100 || concurrency < 1 || concurrency > 5) throw new IllegalArgumentException();
            mappings = mapper.readTree(env.getProperty("CSTORE_APP_MAPPINGS_JSON", "{}"));
            if (mappings == null || !mappings.isObject()) throw new IllegalArgumentException();
            if (!baseUrl.isEmpty()) {
                var uri = URI.create(baseUrl);
                if (!validHttp(uri) || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            }
            if (!authHeaderName.isEmpty() && !authHeaderName.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException();
            if (authHeaderValue.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException();
        } catch (Exception ignored) {
            // Deliberately do not include raw JSON, header values or credentials in startup errors.
            throw new IllegalArgumentException("CStore配置无效：检查Base URL、映射JSON、鉴权头、QPS(1～100)及并发(1～5)。");
        }
    }

    void requireConfigured() {
        if (baseUrl.isEmpty() || !(authNoneConfirmed || (!authHeaderName.isEmpty() && !authHeaderValue.isEmpty())))
            throw problem(503, "CSTORE_NOT_CONFIGURED", "CStore地址或鉴权尚未配置；未请求替代服务。");
    }

    Mapping mapping(String businessApp) {
        JsonNode item = mappings.path(businessApp);
        if (item.isMissingNode() && businessApp.matches("[0-9]{1,32}"))
            return new Mapping(businessApp, new BigInteger(businessApp), "URL_PATH", item);
        if (!item.isObject() || !item.path("accessAppId").isTextual() || item.path("accessAppId").asText().isBlank()
                || !item.path("metadataAppId").isIntegralNumber() || item.path("metadataAppId").bigIntegerValue().signum() < 0
                || !java.util.Set.of("EXACT", "MAPPED", "URL_PATH").contains(item.path("keyMode").asText()))
            throw problem(422, "IDENTITY_UNRESOLVED", "未配置并确认该业务App的CStore AppID和文件身份映射。");
        return new Mapping(item.path("accessAppId").asText(), item.path("metadataAppId").bigIntegerValue(), item.path("keyMode").asText(), item.path("fileKeys"));
    }

    record Mapping(String accessAppId, BigInteger metadataAppId, String keyMode, JsonNode keys) {
        String fileKey(String original) {
            if (keyMode.equals("EXACT")) return original;
            if (keyMode.equals("URL_PATH")) {
                if (original == null || original.isBlank()) throw problem(422, "IDENTITY_UNRESOLVED", "文件标识为空。");
                if (!original.contains("://")) {
                    if (original.startsWith("/") || original.indexOf(':') >= 0) throw problem(422, "IDENTITY_UNRESOLVED", "文件标识须为HTTP(S) URL或不带开头斜杠的fileKey。");
                    return original;
                }
                try {
                    URI uri = URI.create(original);
                    if (!validHttp(uri) || uri.getRawPath() == null || uri.getRawPath().length() <= 1) throw new IllegalArgumentException();
                    // Keep percent escapes and case exactly as exported; query/fragment are not object identity.
                    return uri.getRawPath().substring(1);
                } catch (IllegalArgumentException ignored) { throw problem(422, "IDENTITY_UNRESOLVED", "无法从文件URL取得有效的CStore fileKey。"); }
            }
            JsonNode mapped = keys.path(original);
            if (!mapped.isTextual() || mapped.asText().isBlank()) throw problem(422, "IDENTITY_UNRESOLVED", "此文件尚无已确认的CStore身份映射。");
            return mapped.asText();
        }
    }

    static boolean validHttp(URI uri) {
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
    }
    static PredictionProblem problem(int status, String code, String message) { return new PredictionProblem(status, code, message); }
}
