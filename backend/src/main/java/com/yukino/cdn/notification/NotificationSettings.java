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

@Component
public class NotificationSettings {
    public record Destination(String id, URI webhook, String secret, String label, boolean demoOnly) { }
    private final Map<String, Destination> destinations = new LinkedHashMap<>();
    private final ObjectMapper mapper;

    @Autowired
    public NotificationSettings(@Value("${FEISHU_DESTINATIONS_JSON:}") String json,
                                @Value("${FEISHU_TEST_MODE:false}") boolean testMode, ObjectMapper mapper) {
        this.mapper = mapper;
        try {
            var root = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!root.isObject()) return;
            var names = root.fieldNames();
            while (names.hasNext()) {
                String id = names.next();
                var value = root.path(id);
                URI uri;
                try { uri = URI.create(value.path("webhook").asText("")); }
                catch (IllegalArgumentException error) { continue; }
                String host = uri.getHost();
                boolean official = "https".equals(uri.getScheme()) && "open.feishu.cn".equals(host)
                        && uri.getPath() != null && uri.getPath().startsWith("/open-apis/bot/v2/hook/");
                boolean loopback = testMode && "http".equals(uri.getScheme())
                        && ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host));
                if (!official && !loopback) continue;
                destinations.put(id, new Destination(id, uri, value.path("secret").asText(""),
                        value.path("label").asText(id), value.path("demoOnly").asBoolean(false)));
            }
        } catch (Exception ignored) {
            // An invalid environment setting leaves destinations unavailable.
        }
    }

    public boolean configured(String id) { return destinations.containsKey(id); }
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
