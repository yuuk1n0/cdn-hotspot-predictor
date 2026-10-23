package com.yukino.cdn.health;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {

    @Test
    void reports_application_and_database_are_up() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                return requiredType.cast(1);
            }
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(jdbcTemplate)).build();

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("backend"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void reports_service_unavailable_when_database_is_down() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                throw new DataAccessResourceFailureException("database unavailable");
            }
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(jdbcTemplate)).build();

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.service").value("backend"))
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"));
    }
}
