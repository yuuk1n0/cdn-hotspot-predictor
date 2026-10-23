package com.yukino.cdn.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

@FunctionalInterface
public interface AgentModel {
    JsonNode complete(JsonNode body, Duration remaining) throws Exception;
}
