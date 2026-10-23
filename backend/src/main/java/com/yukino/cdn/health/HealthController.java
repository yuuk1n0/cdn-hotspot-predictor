package com.yukino.cdn.health;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (!Integer.valueOf(1).equals(result)) {
                throw new DataAccessResourceFailureException("Unexpected database health response");
            }
            return ResponseEntity.ok(new HealthResponse("backend", "UP", "UP"));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthResponse("backend", "DOWN", "DOWN"));
        }
    }

    public record HealthResponse(String service, String status, String database) {
    }
}
