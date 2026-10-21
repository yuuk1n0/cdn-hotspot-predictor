package com.yukino.cdn.agent;

public record AgentSettings(String baseUrl, String model, String apiKey) {
    public boolean configured() {
        return nonblank(baseUrl) && nonblank(model) && nonblank(apiKey);
    }

    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
}
