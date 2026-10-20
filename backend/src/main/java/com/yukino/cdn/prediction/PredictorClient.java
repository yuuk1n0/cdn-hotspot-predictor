package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

public interface PredictorClient {
    JsonNode dataset(String datasetId);
    Optional<PythonTask> task(String taskId);
    PythonTask submit(String taskId, PredictionRequest request);

    record PythonTask(String taskId, String status, String runId, PredictionProblem.Error error) {}
}
