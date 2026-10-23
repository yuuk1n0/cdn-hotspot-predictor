package com.yukino.cdn.alert;

import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = FeishuCardActionController.class)
public class FeishuCardActionApiAdvice {
    @ExceptionHandler(PredictionProblem.class)
    public ResponseEntity<?> problem(PredictionProblem error) {
        return ResponseEntity.status(error.httpStatus()).body(Map.of("error", error.error()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidJson(Exception ignored) {
        return problem(new PredictionProblem(422, "INVALID_CARD_CALLBACK", "请求须包含合法 JSON。"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable(Exception ignored) {
        return problem(new PredictionProblem(503, "INCIDENT_STORAGE_UNAVAILABLE", "事故存储不可用。"));
    }
}
