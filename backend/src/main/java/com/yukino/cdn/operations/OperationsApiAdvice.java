package com.yukino.cdn.operations;

import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = OperationsController.class)
public class OperationsApiAdvice {
    @ExceptionHandler(PredictionProblem.class)
    public ResponseEntity<?> problem(PredictionProblem problem) {
        return ResponseEntity.status(problem.httpStatus()).body(problem.error());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalidRequest(Exception ignored) {
        return problem(new PredictionProblem(400, "INVALID_REQUEST", "AppID、业务日期、跨度和运行编号须符合接口契约。"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storageUnavailable(DataAccessException ignored) {
        return problem(new PredictionProblem(503, "OPERATIONS_STORAGE_UNAVAILABLE",
                "业务数据存储不可用；未返回替代数据。请确认数据库连接后重试。"));
    }
}
