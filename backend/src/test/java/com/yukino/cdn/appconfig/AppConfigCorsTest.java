package com.yukino.cdn.appconfig;

import com.yukino.cdn.config.CorsConfig;
import com.yukino.cdn.operations.OperationsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@WebMvcTest(AppConfigController.class)
@Import(CorsConfig.class)
class AppConfigCorsTest {
    @Autowired MockMvc mvc;
    @MockBean OperationsService operations;

    @Test void browserMayPreflightConfigUpdateFromConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/v1/app-configs/10350")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PUT")));
    }

    @Test void browserCannotUpdateFromUnconfiguredOrigin() throws Exception {
        mvc.perform(options("/api/v1/app-configs/10350")
                        .header("Origin", "http://other.test")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden());
    }

    @Test void actualConfigUpdateReturnsCorsHeadersToConfiguredOrigin() throws Exception {
        when(operations.updateConfig(eq("10350"), any())).thenReturn(new ObjectMapper().createObjectNode().put("version", 1));
        mvc.perform(put("/api/v1/app-configs/10350")
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(jsonPath("$.version").value(1));
    }
}
