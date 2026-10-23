package com.yukino.cdn.prediction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("app.prediction")
public record PredictionSettings(
        @DefaultValue("http://127.0.0.1:8000") String pythonBaseUrl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("180s") Duration taskTimeout,
        @DefaultValue("10s") Duration maxRetryDelay
) {
    public PredictionSettings {
        for (Duration value : new Duration[]{connectTimeout, readTimeout, pollInterval, taskTimeout, maxRetryDelay}) {
            if (value == null || value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("预测超时和轮询配置必须为正毫秒数，且不超过 " + Integer.MAX_VALUE + "ms。");
            }
        }
        if (maxRetryDelay.compareTo(pollInterval) < 0) throw new IllegalArgumentException("最大退避间隔不能小于轮询间隔。");
        if (pythonBaseUrl == null || !pythonBaseUrl.matches("https?://.+")) throw new IllegalArgumentException("Python 地址必须为 http(s) URL。");
    }
}
