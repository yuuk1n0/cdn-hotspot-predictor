package com.yukino.cdn.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class NotificationSettings {
    private static final String INVALID_CONFIGURATION = "飞书通知配置无效：检查目的地JSON、Webhook、签名密钥、标签及测试模式。";
    private static final Set<String> DESTINATION_FIELDS = Set.of("webhook", "secret", "label", "demoOnly");
    public record Destination(String id, URI webhook, String secret, String label, boolean demoOnly) { }
    private final Map<String, Destination> destinations = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final int qps;
    private final int perMinute;

    @Autowired
    public NotificationSettings(@Value("${FEISHU_DESTINATIONS_JSON:}") String json,
                                @Value("${FEISHU_TEST_MODE:false}") boolean testMode,
                                @Value("${FEISHU_WEBHOOK_MAX_QPS:4}") int qps,
                                @Value("${FEISHU_WEBHOOK_MAX_PER_MINUTE:60}") int perMinute,
                                ObjectMapper mapper) {
        this.mapper = mapper;
        this.qps = qps;
        this.perMinute = perMinute;
        try {
            if (qps < 1 || qps > 5 || perMinute < 1 || perMinute > 100)
                throw new IllegalArgumentException();
            var root = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            var names = root.fieldNames();
            while (names.hasNext()) {
                String id = names.next();
                var value = root.path(id);
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || !value.isObject())
                    throw new IllegalArgumentException();
                var fields = value.fieldNames();
                while (fields.hasNext()) if (!DESTINATION_FIELDS.contains(fields.next()))
                    throw new IllegalArgumentException();
                if (!value.path("webhook").isTextual() || value.path("webhook").asText().isBlank())
                    throw new IllegalArgumentException();
                URI uri = URI.create(value.path("webhook").asText());
                String host = uri.getHost();
                boolean clean = uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
                boolean official = clean && "https".equalsIgnoreCase(uri.getScheme())
                        && "open.feishu.cn".equalsIgnoreCase(host) && uri.getPort() == -1
                        && uri.getRawPath() != null
                        && uri.getRawPath().matches("/open-apis/bot/v2/hook/[A-Za-z0-9_-]{1,512}");
                boolean loopback = clean && testMode && "http".equalsIgnoreCase(uri.getScheme())
                        && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host));
                if (!official && !loopback) throw new IllegalArgumentException();
                if (value.has("secret") && !value.path("secret").isTextual()) throw new IllegalArgumentException();
                String secret = value.path("secret").asText("");
                if (secret.length() > 512 || secret.chars().anyMatch(c -> c < 32 || c == 127))
                    throw new IllegalArgumentException();
                if (value.has("label") && (!value.path("label").isTextual()
                        || value.path("label").asText().isBlank() || value.path("label").asText().length() > 128))
                    throw new IllegalArgumentException();
                if (value.has("demoOnly") && !value.path("demoOnly").isBoolean())
                    throw new IllegalArgumentException();
                destinations.put(id, new Destination(id, uri, secret,
                        value.has("label") ? value.path("label").asText().strip() : id,
                        value.path("demoOnly").asBoolean(false)));
            }
        } catch (Exception ignored) {
            // Never include the raw JSON, webhook or signing secret in startup errors.
            throw new IllegalArgumentException(INVALID_CONFIGURATION);
        }
    }

    public NotificationSettings(String json, boolean testMode, ObjectMapper mapper) {
        this(json, testMode, 4, 60, mapper);
    }

    public boolean configured(String id) { return destinations.containsKey(id); }
    int qps() { return qps; }
    int perMinute() { return perMinute; }
    public Destination destination(String id) {
        var result = destinations.get(id);
        if (result == null) throw new PredictionProblem(503, "DESTINATION_NOT_CONFIGURED", "通知目标未配置。 ");
        return result;
    }
    public ArrayNode publicDestinations() {
        var result = mapper.createArrayNode();
        destinations.values().forEach(d -> result.addObject().put("id", d.id())
                .put("label", d.label()).put("demoOnly", d.demoOnly()).put("configured", true));
        return result;
    }
}
