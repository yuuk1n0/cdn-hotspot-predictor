package com.yukino.cdn.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Predicate;

@Component
public class FeishuWebhookClient implements NotificationTransport {
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Predicate<String> permits;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Autowired public FeishuWebhookClient(ObjectMapper mapper, NotificationSettings settings) {
        this(mapper, Clock.systemUTC(),
                new FeishuRateLimiter(settings.qps(), settings.perMinute(), System::nanoTime)::tryAcquire);
    }
    public FeishuWebhookClient(ObjectMapper mapper, Clock clock) { this(mapper, clock, ignored -> true); }
    FeishuWebhookClient(ObjectMapper mapper, Clock clock, Predicate<String> permits) {
        this.mapper = mapper;
        this.clock = clock;
        this.permits = permits;
    }

    @Override public Outcome send(NotificationSettings.Destination destination, String text) {
        if (!permits.test(destination.id())) return Outcome.retryable("LOCAL_RATE_LIMIT");
        try {
            ObjectNode body = mapper.createObjectNode().put("msg_type", "text");
            body.putObject("content").put("text", text);
            if (!destination.secret().isBlank()) {
                String timestamp = Long.toString(clock.instant().getEpochSecond());
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec((timestamp + "\n" + destination.secret()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                body.put("timestamp", timestamp);
                body.put("sign", Base64.getEncoder().encodeToString(mac.doFinal(new byte[0])));
            }
            var request = HttpRequest.newBuilder(destination.webhook()).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) return Outcome.retryable("WEBHOOK_SERVER_ERROR");
            if (response.statusCode() != 200) return Outcome.failed("WEBHOOK_HTTP_ERROR");
            var result = mapper.readTree(response.body());
            if (result.has("code")) {
                if (!result.path("code").isNumber()) return Outcome.unknown("WEBHOOK_RESPONSE_UNRECOGNIZED");
                if (result.path("code").asInt() == 0) return Outcome.sent();
                if (result.path("code").asInt() == 11232) return Outcome.retryable("WEBHOOK_RATE_LIMIT");
                return Outcome.failed("WEBHOOK_REJECTED");
            }
            if (result.has("StatusCode")) {
                if (!result.path("StatusCode").isNumber()) return Outcome.unknown("WEBHOOK_RESPONSE_UNRECOGNIZED");
                if (result.path("StatusCode").asInt() == 0) return Outcome.sent();
                return Outcome.failed("WEBHOOK_REJECTED");
            }
            return Outcome.unknown("WEBHOOK_RESPONSE_UNRECOGNIZED");
        } catch (ConnectException error) {
            return Outcome.retryable("CONNECTION_NOT_ESTABLISHED");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Outcome.unknown("DELIVERY_UNCONFIRMED");
        } catch (Exception error) {
            return Outcome.unknown("DELIVERY_UNCONFIRMED");
        }
    }
}
