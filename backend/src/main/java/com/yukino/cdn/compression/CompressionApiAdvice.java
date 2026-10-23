package com.yukino.cdn.compression;

import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice(assignableTypes = CompressionController.class)
public class CompressionApiAdvice {
    @ExceptionHandler(PredictionProblem.class)
    public ResponseEntity<?> problem(PredictionProblem error) {
        return ResponseEntity.status(error.httpStatus()).body(Map.of("error", error.error()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    public ResponseEntity<?> invalid(Exception ignored) {
        return problem(new PredictionProblem(422, "INVALID_REQUEST", "请求须包含合法JSON及必要参数。"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storage(Exception ignored) {
        return problem(new PredictionProblem(503, "COMPRESSION_STORAGE_UNAVAILABLE", "压缩记录存储不可用；未确认登记，请核对目标库的已批准业务表。"));
    }
}
