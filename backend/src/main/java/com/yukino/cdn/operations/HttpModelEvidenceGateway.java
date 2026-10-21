package com.yukino.cdn.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.prediction.PredictionSettings;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import static com.yukino.cdn.operations.OperationsSupport.*;

@Component
public class HttpModelEvidenceGateway implements ModelEvidenceGateway {
    private final RestClient client;
    public HttpModelEvidenceGateway(PredictionSettings settings) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(settings.pythonBaseUrl()).requestFactory(factory).build();
    }
    public ObjectNode metrics(String app, int horizon) {
        id(app);
        if (horizon != 1 && horizon != 7) throw problem(422, "INVALID_HORIZON", "跨度须为1或7。");
        try {
            JsonNode value = client.get().uri("/api/v1/models/{app}/metrics?horizonDays={horizon}", app, horizon).retrieve().body(JsonNode.class);
            if (value == null || !value.isObject() || !app.equals(value.path("appId").asText()) || value.path("horizonDays").asInt() != horizon || !value.path("availableModels").isArray())
                throw problem(503, "MODEL_EVIDENCE_INVALID", "模型证据响应归属或结构不符。");
            return (ObjectNode) value;
        } catch (com.yukino.cdn.prediction.PredictionProblem known) { throw known; }
        catch (RuntimeException dependency) { throw problem(503, "MODEL_EVIDENCE_UNAVAILABLE", "模型证据接口不可用，未使用静态样例代替。"); }
    }
}
