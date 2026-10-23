package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuOpenApiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cachesTenantTokenAndCreatesPrivateIncidentGroup() throws Exception {
        var tokenCalls = new AtomicInteger();
        var createBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange -> {
            tokenCalls.incrementAndGet();
            reply(exchange, 200, "{\"code\":0,\"tenant_access_token\":\"tenant-token\",\"expire\":7200}");
        });
        server.createContext("/open-apis/im/v1/chats", exchange -> {
            createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, "{\"code\":0,\"data\":{\"chat_id\":\"oc_created\"}}");
        });
        server.start();
        try {
            var settings = new AlertSettings("app-id", "app-secret", "callback-token",
                    "http://127.0.0.1:" + server.getAddress().getPort(), true);
            var client = new FeishuOpenApiClient(settings, mapper,
                    Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));

            assertEquals("oc_created", client.createChat("[P1] 10350-content-IMPORT_FAILED", "incident"));
            assertEquals("oc_created", client.createChat("[P1] second", "incident"));

            assertEquals(1, tokenCalls.get());
            assertEquals("Bearer tenant-token", authorization.get());
            JsonNode body = mapper.readTree(createBody.get());
            assertEquals("[P1] second", body.path("name").asText());
            assertEquals("group", body.path("chat_mode").asText());
            assertEquals("private", body.path("chat_type").asText());
            assertFalse(settings.toString().contains("app-secret"));
            assertFalse(settings.toString().contains("tenant-token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addsMembersSendsCardAndValidatesChatAndUsers() throws Exception {
        var memberBody = new AtomicReference<String>();
        var messageBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange ->
                reply(exchange, 200, "{\"code\":0,\"tenant_access_token\":\"tenant-token\",\"expire\":7200}"));
        server.createContext("/open-apis/im/v1/chats/oc_created/members", exchange -> {
            memberBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "{\"code\":0,\"data\":{\"invalid_id_list\":[],\"not_existed_id_list\":[],\"pending_approval_id_list\":[]}}");
        });
        server.createContext("/open-apis/im/v1/messages", exchange -> {
            messageBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "{\"code\":0,\"data\":{\"message_id\":\"om_created\"}}");
        });
        server.createContext("/open-apis/im/v1/chats/oc_created", exchange ->
                reply(exchange, 200, "{\"code\":0,\"data\":{\"chat_status\":\"normal\"}}"));
        server.createContext("/open-apis/contact/v3/users/", exchange ->
                reply(exchange, 200, "{\"code\":0,\"data\":{\"user\":{\"open_id\":\"ou_owner\"}}}"));
        server.start();
        try {
            var settings = new AlertSettings("app-id", "app-secret", "callback-token",
                    "http://127.0.0.1:" + server.getAddress().getPort(), true);
            var client = new FeishuOpenApiClient(settings, mapper,
                    Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));

            client.addMembers("oc_created", java.util.List.of("ou_owner", "ou_backup"));
            assertEquals("om_created", client.sendCard("oc_created", "{\"schema\":\"2.0\"}", "alert-open-1"));
            client.validateChat("oc_created");
            client.validateUsers(java.util.List.of("ou_owner"));

            assertEquals("{\"id_list\":[\"ou_owner\",\"ou_backup\"]}", memberBody.get());
            JsonNode message = mapper.readTree(messageBody.get());
            assertEquals("oc_created", message.path("receive_id").asText());
            assertEquals("interactive", message.path("msg_type").asText());
            assertEquals("{\"schema\":\"2.0\"}", message.path("content").asText());
            assertEquals("alert-open-1", message.path("uuid").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void memberPartialFailureIsNotReportedAsSuccess() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange ->
                reply(exchange, 200, "{\"code\":0,\"tenant_access_token\":\"tenant-token\",\"expire\":7200}"));
        server.createContext("/open-apis/im/v1/chats/oc_created/members", exchange ->
                reply(exchange, 200, "{\"code\":0,\"data\":{\"invalid_id_list\":[\"ou_left\"],\"not_existed_id_list\":[],\"pending_approval_id_list\":[]}}"));
        server.start();
        try {
            var settings = new AlertSettings("app-id", "app-secret", "", "", "http://127.0.0.1:" + server.getAddress().getPort(), true);
            var client = new FeishuOpenApiClient(settings, mapper, Clock.systemUTC());

            var error = assertThrows(PredictionProblem.class,
                    () -> client.addMembers("oc_created", java.util.List.of("ou_left")));

            assertEquals("FEISHU_MEMBER_PARTIAL", error.code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unauthorizedMessageRefreshesTokenOnce() throws Exception {
        var tokenCalls = new AtomicInteger();
        var messageCalls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange -> {
            int attempt = tokenCalls.incrementAndGet();
            reply(exchange, 200, "{\"code\":0,\"tenant_access_token\":\"tenant-" + attempt + "\",\"expire\":7200}");
        });
        server.createContext("/open-apis/im/v1/messages", exchange -> {
            if (messageCalls.incrementAndGet() == 1) reply(exchange, 401, "{\"code\":99991663,\"msg\":\"expired\"}");
            else reply(exchange, 200, "{\"code\":0,\"data\":{\"message_id\":\"om_retry\"}}");
        });
        server.start();
        try {
            var settings = new AlertSettings("app-id", "app-secret", "", "", "http://127.0.0.1:" + server.getAddress().getPort(), true);
            var client = new FeishuOpenApiClient(settings, mapper, Clock.systemUTC());

            assertEquals("om_retry", client.sendCard("oc_created", "{}", "alert-retry"));
            assertEquals(2, tokenCalls.get());
            assertEquals(2, messageCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ingestAndCallbackTokensAreCheckedWithoutExposingThem() {
        var settings = new AlertSettings("app-id", "app-secret", "callback-token", "ingest-token",
                "https://open.feishu.cn", false);

        settings.verifyIngestToken("ingest-token");
        assertTrue(settings.callbackMatches("callback-token"));
        assertEquals(401, assertThrows(PredictionProblem.class,
                () -> settings.verifyIngestToken("wrong")).httpStatus());
        assertFalse(settings.callbackMatches("wrong"));
        assertFalse(settings.toString().contains("app-secret"));
        assertFalse(settings.toString().contains("callback-token"));
        assertFalse(settings.toString().contains("ingest-token"));
    }

    @Test
    void rateLimitAndServerErrorsAreMarkedRetryable() throws Exception {
        var status = new AtomicInteger(429);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange ->
                reply(exchange, 200, "{\"code\":0,\"tenant_access_token\":\"tenant-token\",\"expire\":7200}"));
        server.createContext("/open-apis/im/v1/messages", exchange ->
                reply(exchange, status.get(), "{\"code\":0}"));
        server.start();
        try {
            var settings = new AlertSettings("app-id", "app-secret", "", "", "http://127.0.0.1:" + server.getAddress().getPort(), true);
            var client = new FeishuOpenApiClient(settings, mapper, Clock.systemUTC());

            assertTrue(assertThrows(PredictionProblem.class,
                    () -> client.sendCard("oc_created", "{}", "rate-limit")).retryable());
            status.set(503);
            assertTrue(assertThrows(PredictionProblem.class,
                    () -> client.sendCard("oc_created", "{}", "server-error")).retryable());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonOfficialOrInsecureBaseUrlsCannotBeConfiguredForProduction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlertSettings("app-id", "app-secret", "", "", "http://127.0.0.1:9000", false));
        assertThrows(IllegalArgumentException.class,
                () -> new AlertSettings("app-id", "app-secret", "", "", "https://example.com", false));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
