package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;

public class PredictorHttpClient implements PredictorClient {
    private final RestClient http;
    private final ObjectMapper mapper;

    public PredictorHttpClient(RestClient http, ObjectMapper mapper) { this.http = http; this.mapper = mapper; }

    @Override
    public JsonNode dataset(String datasetId) {
        return exchange(() -> http.get().uri("/api/v1/datasets/{id}", datasetId), 200, "dataset");
    }

    @Override
    public Optional<PythonTask> task(String taskId) {
        JsonNode body = exchange(() -> http.get().uri("/api/v1/tasks/{id}", taskId), 200, "task");
        return body == null ? Optional.empty() : Optional.of(parseTask(body, taskId));
    }

    @Override
    public PythonTask submit(String taskId, PredictionRequest request) {
        // The modelVersion is forwarded verbatim; Java never selects or falls back to Mock.
        JsonNode body = exchange(() -> http.post().uri("/api/v1/predictions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("taskId", taskId, "datasetId", request.datasetId(), "appId", request.appId(),
                        "cutoffDate", request.cutoffDate().toString(), "horizonDays", request.horizonDays(), "modelVersion", request.modelVersion())), 202, "submit");
        return parseTask(body, taskId);
    }

    @Override
    public JsonNode items(String runId) {
        return exchange(() -> http.get().uri("/api/v1/predictions/{id}/items?page=1&pageSize=50", runId), 200, "items");
    }

    private JsonNode exchange(java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request, int expectedStatus, String operation) {
        try {
            var response = request.get().retrieve().toEntity(JsonNode.class);
            if (response.getStatusCode().value() != expectedStatus || response.getBody() == null || !response.getBody().isObject()) {
                throw invalid();
            }
            return response.getBody();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 404 && operation.equals("task")) return null;
            if (status == 404 && operation.equals("dataset")) throw new PredictionProblem(404, "DATASET_NOT_FOUND", "Python 数据集不存在。");
            if (status == 404 && operation.equals("submit")) throw PredictionProblem.retryable("PYTHON_API_UNAVAILABLE", "Python 预测接口尚不可用；等待原任务重试，不代表算法失败。");
            if (status == 404 && operation.equals("items")) throw new PredictionProblem(422, "INVALID_RESULT", "Python 成功任务的完整结果不存在。");
            if (status >= 500 || status == 408 || status == 429 || status == 425) {
                throw PredictionProblem.retryable("PYTHON_UNAVAILABLE", "Python 暂不可用或请求受限（HTTP " + status + "），将重试原任务。");
            }
            PredictionProblem.Error error = parseError(exception.getResponseBodyAsString());
            throw new PredictionProblem(status, error.code(), error.message());
        } catch (ResourceAccessException exception) {
            throw PredictionProblem.retryable("PYTHON_UNAVAILABLE", "连接 Python 失败或单次请求超时，将重试原任务。");
        } catch (RestClientException exception) {
            throw invalid();
        }
    }

    private PythonTask parseTask(JsonNode body, String expectedId) {
        if (body == null || !body.path("taskId").isTextual() || !body.path("status").isTextual()
                || (body.hasNonNull("runId") && !body.path("runId").isTextual())) throw invalid();
        PredictionProblem.Error error = null;
        if (body.hasNonNull("error")) {
            JsonNode value = body.path("error");
            if (!value.path("code").isTextual() || value.path("code").asText().isBlank() || !value.path("message").isTextual()) throw invalid();
            error = new PredictionProblem.Error(value.path("code").textValue(), value.path("message").textValue());
        }
        PythonTask result = new PythonTask(body.path("taskId").textValue(), body.path("status").textValue(),
                body.hasNonNull("runId") ? body.path("runId").textValue() : null, error);
        PredictionWorker.validatePythonTask(expectedId, result);
        return result;
    }

    private PredictionProblem.Error parseError(String body) {
        try {
            JsonNode value = mapper.readTree(body).path("error");
            if (value.path("code").isTextual() && value.path("message").isTextual()) {
                return new PredictionProblem.Error(value.path("code").textValue(), value.path("message").textValue());
            }
        } catch (Exception ignored) { /* Do not expose arbitrary HTML or internal transport details. */ }
        return new PredictionProblem.Error("PYTHON_REQUEST_REJECTED", "Python 拒绝了预测请求。");
    }

    private static PredictionProblem invalid() { return new PredictionProblem(422, "INVALID_PYTHON_RESPONSE", "Python HTTP 响应不符合预测契约。"); }
}
