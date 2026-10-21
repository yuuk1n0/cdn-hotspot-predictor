package com.yukino.cdn.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.operations.OperationsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class AgentConfiguration {
    @Bean AgentSettings agentSettings() {
        return new AgentSettings(System.getenv("AGENT_BASE_URL"), System.getenv("AGENT_MODEL"), System.getenv("AGENT_API_KEY"));
    }

    @Bean AgentService agentService(OperationsService operations, ObjectMapper mapper, AgentSettings settings) {
        HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(60));
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (settings.configured()) builder.baseUrl(settings.baseUrl());
        RestClient client = builder.build();
        return new AgentService(operations, new AgentHttpClient(client, settings, mapper), mapper,
                Clock.systemUTC(), settings, Duration.ofSeconds(60));
    }
}
