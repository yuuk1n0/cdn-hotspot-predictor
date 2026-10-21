package com.yukino.cdn.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import static com.yukino.cdn.files.CStoreSettings.problem;

@Component
public final class CStoreClient {
    private final CStoreSettings settings;
    private final RestClient http;
    private final CStoreLimiter limiter;
    @Autowired
    public CStoreClient(CStoreSettings settings) {
        this(settings, client(settings), new CStoreLimiter(settings.qps, settings.concurrency, System::nanoTime));
    }
    CStoreClient(CStoreSettings settings, RestClient http, CStoreLimiter limiter) { this.settings = settings; this.http = http; this.limiter = limiter; }
    private static RestClient client(CStoreSettings settings) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(settings.baseUrl.isEmpty() ? "http://unconfigured.invalid" : settings.baseUrl).requestFactory(factory).build();
    }
    private JsonNode post(String path, Object body) {
        settings.requireConfigured();
        try (var ignored = limiter.acquire()) {
            var request = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
            if (!settings.authHeaderName.isEmpty()) request.header(settings.authHeaderName, settings.authHeaderValue);
            JsonNode response = request.retrieve().body(JsonNode.class);
            if (response == null || !response.path("code").isIntegralNumber() || !response.path("code").bigIntegerValue().equals(java.math.BigInteger.ZERO))
                throw problem(502, "CSTORE_UPSTREAM_REJECTED", "CStore未确认请求成功，请核对权限与文件身份。");
            if (!response.path("data").isArray()) throw invalid();
            return response.path("data");
        } catch (PredictionProblem error) { throw error; }
        catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status == 401 || status == 403) throw problem(503, "CSTORE_AUTH_REJECTED", "CStore鉴权或权限校验失败，请联系管理员。");
            if (status == 429) throw problem(429, "CSTORE_RATE_LIMITED", "CStore请求受限，请稍后重试。");
            throw problem(503, "CSTORE_UNAVAILABLE", "CStore服务暂不可用，未返回替代数据。");
        } catch (RuntimeException ignored) { throw problem(503, "CSTORE_UNAVAILABLE", "CStore请求失败或超时，未返回替代数据。"); }
    }

    Map<String, Meta> metadata(CStoreSettings.Mapping mapping, List<String> keys) {
        if (keys.isEmpty() || keys.size() > 10) throw problem(422, "INVALID_FILE_REQUEST", "单次上游元信息查询须为1至10个文件。");
        if (!settings.bytesConfirmed) throw problem(503, "CSTORE_UNIT_UNCONFIRMED", "尚未确认CStore fsize的字节单位，不能展示为实际大小。");
        JsonNode data = post("/cstore/api/v2/file/meta", Map.of("appId", mapping.metadataAppId(), "fileKeys", keys));
        Map<String, Meta> result = new HashMap<>();
        for (var item : data) {
            String key = item.path("fileKey").isTextual() ? item.path("fileKey").textValue() : null;
            if (key == null || !keys.contains(key) || result.containsKey(key)) throw invalid();
            if (!item.path("error").isBoolean()) { result.put(key, new Meta(null, null, "CSTORE_INVALID_RESPONSE")); continue; }
            if (item.path("error").booleanValue()) { result.put(key, new Meta(null, null, "FILE_METADATA_UNAVAILABLE")); continue; }
            JsonNode size = item.has("fSize") ? item.path("fSize") : item.path("fsize");
            if (!size.isIntegralNumber() || size.bigIntegerValue().signum() < 0) { result.put(key, new Meta(null, null, "CSTORE_INVALID_RESPONSE")); continue; }
            String mime = safeMime(item.path("mimeType"));
            result.put(key, new Meta(size.bigIntegerValue().toString(), mime, null));
        }
        keys.forEach(key -> result.putIfAbsent(key, new Meta(null, null, "FILE_METADATA_MISSING")));
        return result;
    }

    String access(CStoreSettings.Mapping mapping, String key) {
        var data = post("/cstore/api/v3/downloadUrl", Map.of("appId", mapping.accessAppId(), "fileKeys", List.of(key)));
        if (data.size() != 1 || !data.get(0).path("fileKey").isTextual() || !key.equals(data.get(0).path("fileKey").textValue()) || !data.get(0).path("downloadUrl").isTextual()) throw invalid();
        String url = data.get(0).path("downloadUrl").textValue();
        if (url.isEmpty()) throw problem(422, "FILE_ACCESS_UNAVAILABLE", "文件不存在或无法获取访问地址。");
        try { if (!CStoreSettings.validHttp(URI.create(url)) || url.length() > 16384) throw invalid(); }
        catch (IllegalArgumentException ignored) { throw invalid(); }
        return url;
    }
    private static String safeMime(JsonNode value) {
        if (!value.isTextual()) return null;
        String mime = value.textValue();
        return mime.length() <= 128 && mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") ? mime : null;
    }
    record Meta(String bytes, String contentType, String errorCode) { }
    private static PredictionProblem invalid() { return problem(502, "CSTORE_INVALID_RESPONSE", "CStore返回内容不符合已确认的文件协议。"); }
}
