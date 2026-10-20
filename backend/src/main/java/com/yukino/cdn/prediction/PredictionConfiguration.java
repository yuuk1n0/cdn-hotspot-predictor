package com.yukino.cdn.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PredictionSettings.class)
public class PredictionConfiguration {
    @Bean
    Clock predictionClock() { return Clock.systemUTC(); }

    @Bean
    PredictorClient predictorClient(PredictionSettings settings, ObjectMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) settings.connectTimeout().toMillis());
        factory.setReadTimeout((int) settings.readTimeout().toMillis());
        return new PredictorHttpClient(RestClient.builder().baseUrl(settings.pythonBaseUrl()).requestFactory(factory).build(), mapper);
    }

    @Bean
    PredictionWorker predictionWorker(PredictionRepository repository, PredictorClient client, PredictionSettings settings, Clock clock) {
        return new PredictionWorker(repository, client, settings, clock);
    }
}
