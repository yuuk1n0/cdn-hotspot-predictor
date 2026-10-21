package com.yukino.cdn.agent;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = AgentController.class)
public class AgentApiAdvice {
    @ExceptionHandler(AgentProblem.class)
    public ResponseEntity<?> problem(AgentProblem problem) {
        return ResponseEntity.status(problem.httpStatus()).body(Map.of("error", Map.of("code", problem.code(), "message", problem.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidJson(Exception ignored) {
        return problem(new AgentProblem(422, "AGENT_INVALID_REQUEST", "请求须包含合法 JSON。"));
    }
}
