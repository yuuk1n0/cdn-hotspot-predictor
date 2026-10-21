package com.yukino.cdn.files;

import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes = CStoreController.class)
public class CStoreApiAdvice {
    @ExceptionHandler(PredictionProblem.class)
    public ResponseEntity<?> problem(PredictionProblem error) { return ResponseEntity.status(error.httpStatus()).cacheControl(CacheControl.noStore()).body(Map.of("error", error.error())); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalid(Exception ignored) { return problem(new PredictionProblem(422, "INVALID_FILE_REQUEST", "请提交有效JSON文件查询参数。")); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storage(Exception ignored) { return problem(new PredictionProblem(503, "FILE_CONTEXT_UNAVAILABLE", "暂时无法核对可信业务运行，未调用CStore。")); }
}
