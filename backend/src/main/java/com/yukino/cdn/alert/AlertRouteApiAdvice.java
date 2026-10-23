package com.yukino.cdn.alert;

import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = AlertRouteController.class)
public class AlertRouteApiAdvice {
    @ExceptionHandler(PredictionProblem.class)
    public ResponseEntity<?> problem(PredictionProblem error) {
        return ResponseEntity.status(error.httpStatus()).body(Map.of("error", error.error()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidJson(Exception ignored) {
        return problem(new PredictionProblem(422, "INVALID_CONFIG", "请求须包含合法 JSON。"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable(Exception ignored) {
        return problem(new PredictionProblem(503, "ALERT_ROUTE_STORAGE_UNAVAILABLE",
                "告警配置存储不可用；未确认保存。"));
    }
}
