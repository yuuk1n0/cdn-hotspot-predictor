package com.yukino.cdn.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FeishuWebhookClientTest {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void textAndOptionalSignatureMatchProtocolAndBusinessCodeIsRequired() throws Exception {
        var seen = new AtomicReference<String>();
        var response = new AtomicReference<>("{\"code\":0}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            String settings = "{\"test\":{\"webhook\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/hook\",\"secret\":\"signed-secret\",\"demoOnly\":true}}";
            var destination = new NotificationSettings(settings, true, mapper).destination("test");
            var clock = Clock.fixed(Instant.parse("2026-10-21T01:00:00Z"), ZoneOffset.UTC);
            var client = new FeishuWebhookClient(mapper, clock);
            assertEquals("SENT", client.send(destination, "DEMO 测试").status());
            var sent = mapper.readTree(seen.get());
            assertEquals("text", sent.path("msg_type").asText());
            assertEquals("DEMO 测试", sent.path("content").path("text").asText());
            String timestamp = Long.toString(clock.instant().getEpochSecond());
            assertEquals(timestamp, sent.path("timestamp").asText());
            var hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec((timestamp + "\nsigned-secret").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            assertEquals(Base64.getEncoder().encodeToString(hmac.doFinal(new byte[0])), sent.path("sign").asText());
            assertFalse(seen.get().contains("signed-secret"));
            response.set("{\"code\":19021,\"msg\":\"credential signed-secret must never leak\"}");
            var failed = client.send(destination,"DEMO");
            assertEquals("FAILED", failed.status());
            assertFalse(failed.code().contains("signed-secret"));
            response.set("{\"code\":19021,\"StatusCode\":0}");
            assertEquals("FAILED", client.send(destination,"DEMO").status());
            response.set("{}"); assertEquals("UNKNOWN", client.send(destination,"DEMO").status());
            response.set("{\"StatusCode\":0,\"StatusMessage\":\"success\"}"); assertEquals("SENT", client.send(destination,"DEMO").status());
            response.set("{\"code\":11232}"); assertTrue(client.send(destination,"DEMO").retryable());
        } finally { server.stop(0); }
    }
    @Test void insecureOrArbitraryDestinationsAreNotConfiguredEvenInTestMode() {
        String values = "{\"remote\":{\"webhook\":\"http://example.com/hook\"},\"loopback\":{\"webhook\":\"http://127.0.0.1:1234/hook\"},\"foreign\":{\"webhook\":\"https://example.com/hook\"},\"official\":{\"webhook\":\"https://open.feishu.cn/open-apis/bot/v2/hook/token\",\"demoOnly\":true}}";
        var normal = new NotificationSettings(values, false, mapper);
        assertFalse(normal.configured("loopback")); assertTrue(normal.configured("official"));
        var testing = new NotificationSettings(values, true, mapper);
        assertTrue(testing.configured("loopback")); assertFalse(testing.configured("remote")); assertFalse(testing.configured("foreign"));
        assertFalse(testing.publicDestinations().toString().contains("token"));
    }
}
