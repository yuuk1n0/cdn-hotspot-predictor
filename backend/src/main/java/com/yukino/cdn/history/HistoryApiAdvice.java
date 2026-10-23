package com.yukino.cdn.history;

import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes=HistoryController.class)
public class HistoryApiAdvice {
    @ExceptionHandler(PredictionProblem.class)
    public ResponseEntity<?> problem(PredictionProblem problem) {
        return ResponseEntity.status(problem.httpStatus()).body(Map.of("error",problem.error()));
    }
}
