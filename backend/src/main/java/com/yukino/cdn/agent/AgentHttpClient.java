package com.yukino.cdn.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.time.Duration;

public final class AgentHttpClient implements AgentModel {
    private final RestClient client;
    private final AgentSettings settings;
    private final ObjectMapper mapper;

    public AgentHttpClient(RestClient client, AgentSettings settings, ObjectMapper mapper) {
        this.client = client;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override public JsonNode complete(JsonNode body, Duration remaining) throws Exception {
        String response = client.post().uri("/chat/completions")
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .body(body).retrieve().body(String.class);
        return mapper.readTree(response);
    }
}
