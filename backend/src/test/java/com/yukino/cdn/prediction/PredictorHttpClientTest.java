package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PredictorHttpClientTest {
    private MockRestServiceServer server;
    private PredictorHttpClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("http://python.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PredictorHttpClient(builder.build(), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void sendsOnlyTaskIdAndFiveBusinessParametersAndRequires202() {
        server.expect(requestTo("http://python.test/api/v1/predictions")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"taskId":"task_original","datasetId":"ds_one","appId":"10350","cutoffDate":"2026-10-06",
                        "horizonDays":7,"modelVersion":"actual-v1"}
                        """, true))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"taskId\":\"task_original\",\"status\":\"PENDING\"}"));
        var task = client.submit("task_original", request());
        assertEquals("task_original", task.taskId());
        assertEquals("PENDING", task.status());
        server.verify();
    }

    @Test
    void task404PermitsDispatchButPrediction404IsDependencyUnavailability() {
        server.expect(requestTo("http://python.test/api/v1/tasks/task_original")).andRespond(withResourceNotFound());
        server.expect(requestTo("http://python.test/api/v1/predictions")).andRespond(withResourceNotFound());
        assertTrue(client.task("task_original").isEmpty());
        var error = assertThrows(PredictionProblem.class, () -> client.submit("task_original", request()));
        assertTrue(error.retryable());
        assertEquals("PYTHON_API_UNAVAILABLE", error.code());
        server.verify();
    }

    @Test
    void dataset404IsAMeaningfulBusinessFailure() {
        server.expect(requestTo("http://python.test/api/v1/datasets/ds_missing")).andRespond(withResourceNotFound());
        var error = assertThrows(PredictionProblem.class, () -> client.dataset("ds_missing"));
        assertEquals("DATASET_NOT_FOUND", error.code());
        assertFalse(error.retryable());
    }

    @Test
    void fiveHundredAndReadTimeoutAreRetryableNotAlgorithmFailures() {
        server.expect(requestTo("http://python.test/api/v1/tasks/task_original")).andRespond(withServerError());
        server.expect(requestTo("http://python.test/api/v1/tasks/task_original"))
                .andRespond(request -> { throw new SocketTimeoutException("timed out"); });
        assertTrue(assertThrows(PredictionProblem.class, () -> client.task("task_original")).retryable());
        assertTrue(assertThrows(PredictionProblem.class, () -> client.task("task_original")).retryable());
    }

    @Test
    void realModelRejectionIsReportedWithoutAnyMockFallback() {
        server.expect(requestTo("http://python.test/api/v1/predictions"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"MODEL_NOT_IMPLEMENTED\",\"message\":\"真实模型未实现\"}}"));
        var error = assertThrows(PredictionProblem.class, () -> client.submit("task_original", request()));
        assertEquals("MODEL_NOT_IMPLEMENTED", error.code());
        assertFalse(error.retryable());
        server.verify();
    }

    @Test
    void wrongTaskIdMalformedJsonAndWrongSuccessStatusFailTheContract() {
        server.expect(requestTo("http://python.test/api/v1/tasks/task_original"))
                .andRespond(withSuccess("{\"taskId\":\"other\",\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://python.test/api/v1/tasks/task_original"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://python.test/api/v1/predictions"))
                .andRespond(withSuccess("{\"taskId\":\"task_original\",\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        assertEquals("INVALID_PYTHON_RESPONSE", assertThrows(PredictionProblem.class, () -> client.task("task_original")).code());
        assertEquals("INVALID_PYTHON_RESPONSE", assertThrows(PredictionProblem.class, () -> client.task("task_original")).code());
        assertEquals("INVALID_PYTHON_RESPONSE", assertThrows(PredictionProblem.class, () -> client.submit("task_original", request())).code());
        server.verify();
    }

    private PredictionRequest request() {
        return new PredictionRequest("ds_one", "10350", LocalDate.of(2026, 10, 6), 7, "actual-v1");
    }
}
