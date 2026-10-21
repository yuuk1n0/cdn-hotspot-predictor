package com.yukino.cdn.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
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
    @Test void officialDestinationIsAcceptedWithoutExposingWebhookOrSecret() {
        String values = "{\"official\":{\"webhook\":\"https://open.feishu.cn/open-apis/bot/v2/hook/token\",\"secret\":\"private-signing-secret\",\"label\":\"业务群\",\"demoOnly\":false}}";
        var settings = new NotificationSettings(values, false, mapper);
        assertTrue(settings.configured("official"));
        String publicJson = settings.publicDestinations().toString();
        assertFalse(publicJson.contains("token"));
        assertFalse(publicJson.contains("private-signing-secret"));
    }

    @Test void invalidDestinationConfigurationFailsFastWithoutLeakingRawValues() {
        for (String values : java.util.List.of(
                "not-json",
                "[]",
                "{\"remote\":{\"webhook\":\"http://example.com/hook\",\"secret\":\"do-not-leak\"}}",
                "{\"foreign\":{\"webhook\":\"https://example.com/hook\"}}",
                "{\"official\":{\"webhook\":\"https://open.feishu.cn/open-apis/bot/v2/hook/token?redirect=bad\"}}",
                "{\"official\":{\"webhook\":\"https://open.feishu.cn/open-apis/bot/v2/hook/token\",\"label\":\"\",\"demoOnly\":\"false\"}}")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new NotificationSettings(values, false, mapper));
            assertEquals("飞书通知配置无效：检查目的地JSON、Webhook、签名密钥、标签及测试模式。", error.getMessage());
            assertFalse(error.getMessage().contains("do-not-leak"));
        }
    }

    @Test void loopbackDestinationRequiresExplicitTestMode() {
        String values = "{\"loopback\":{\"webhook\":\"http://127.0.0.1:1234/hook\",\"label\":\"测试群\",\"demoOnly\":true}}";
        assertThrows(IllegalArgumentException.class, () -> new NotificationSettings(values, false, mapper));
        assertTrue(new NotificationSettings(values, true, mapper).configured("loopback"));
    }

    @Test void rateLimitsAcceptOfficialBoundsAndRejectOutOfRangeValues() {
        var minimum = new NotificationSettings("{}", false, 1, 1, mapper);
        assertEquals(1, minimum.qps());
        assertEquals(1, minimum.perMinute());
        var maximum = new NotificationSettings("{}", false, 5, 100, mapper);
        assertEquals(5, maximum.qps());
        assertEquals(100, maximum.perMinute());
        for (int qps : new int[]{0, 6})
            assertThrows(IllegalArgumentException.class,
                    () -> new NotificationSettings("{}", false, qps, 60, mapper));
        for (int perMinute : new int[]{0, 101})
            assertThrows(IllegalArgumentException.class,
                    () -> new NotificationSettings("{}", false, 4, perMinute, mapper));
    }

    @Test void springBindsTheExistingWebhookRateEnvironmentNames() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NotificationSettings.class)
                .withPropertyValues("FEISHU_WEBHOOK_MAX_QPS=5", "FEISHU_WEBHOOK_MAX_PER_MINUTE=100")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(5, context.getBean(NotificationSettings.class).qps());
                    assertEquals(100, context.getBean(NotificationSettings.class).perMinute());
                });
    }

    @Test void localRateLimitIsRetryableAndDoesNotSendHttpRequest() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String values = "{\"business\":{\"webhook\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/hook\",\"demoOnly\":false}}";
            var destination = new NotificationSettings(values, true, mapper).destination("business");
            var client = new FeishuWebhookClient(mapper, Clock.systemUTC(), ignored -> false);
            var outcome = client.send(destination, "不会发送");
            assertTrue(outcome.retryable());
            assertEquals("LOCAL_RATE_LIMIT", outcome.code());
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }
}
