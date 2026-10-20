package com.yukino.cdn.prediction;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = PredictionController.class)
public class PredictionApiAdvice {
    @ExceptionHandler(PredictionProblem.class)
    public ResponseEntity<?> problem(PredictionProblem problem) {
        return ResponseEntity.status(problem.httpStatus()).body(Map.of("error", problem.error()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    public ResponseEntity<?> invalidRequest(Exception ignored) {
        return problem(PredictionRequest.invalid("请求须包含合法 JSON 和 Idempotency-Key。"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storageUnavailable(DataAccessException ignored) {
        return problem(new PredictionProblem(503, "TASK_STORAGE_UNAVAILABLE", "任务存储不可用；请确认数据库连接及已审批的增量表变更。未确认接收，可使用原幂等键重试。"));
    }
}
