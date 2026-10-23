package com.yukino.cdn.operations;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yukino.cdn.prediction.PredictionProblem;
import com.yukino.cdn.prediction.PredictionSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpModelEvidenceGatewayTest {
    @Test
    void reviewPreservesStructuredPythonFailure() throws Exception {
        var server = server(409, """
                {"error":{"code":"MYSQL_REQUIRED","message":"模型评审必须读取真实 MySQL 观测。"}}
                """);
        try {
            var problem = assertThrows(PredictionProblem.class,
                    () -> gateway(server).review("10350", 1));

            assertEquals(409, problem.httpStatus());
            assertEquals("MYSQL_REQUIRED", problem.code());
            assertEquals("模型评审必须读取真实 MySQL 观测。", problem.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedPythonFailureRemainsBoundedDependencyError() throws Exception {
        var server = server(500, "not-json");
        try {
            var problem = assertThrows(PredictionProblem.class,
                    () -> gateway(server).review("10350", 1));

            assertEquals(503, problem.httpStatus());
            assertEquals("MODEL_REVIEW_UNAVAILABLE", problem.code());
        } finally {
            server.stop(0);
        }
    }

    private HttpModelEvidenceGateway gateway(HttpServer server) {
        var timeout = Duration.ofSeconds(2);
        var settings = new PredictionSettings("http://127.0.0.1:" + server.getAddress().getPort(),
                timeout, timeout, timeout, timeout, timeout);
        return new HttpModelEvidenceGateway(settings);
    }

    private HttpServer server(int status, String body) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models/10350/review", exchange -> reply(exchange, status, body));
        server.start();
        return server;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
