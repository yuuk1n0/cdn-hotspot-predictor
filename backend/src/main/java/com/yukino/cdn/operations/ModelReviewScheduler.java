package com.yukino.cdn.operations;

import com.yukino.cdn.prediction.PredictionProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AppID-level periodic review; one App failure never blocks another App. */
@Component
public class ModelReviewScheduler {
    private static final Logger log = LoggerFactory.getLogger(ModelReviewScheduler.class);
    private final OperationsService operations;

    public ModelReviewScheduler(OperationsService operations) { this.operations = operations; }

    @Scheduled(cron = "${app.model-review.cron:0 0 10 * * *}", zone = "Asia/Shanghai")
    public void reviewConfiguredApps() {
        for (String app : operations.configuredApps()) {
            try {
                var config = operations.config(app);
                if (!config.path("modelReviewEnabled").asBoolean(false)) continue;
                for (int horizon : new int[]{1, 7}) {
                    try {
                        operations.suggestion(app, horizon, null);
                    } catch (PredictionProblem problem) {
                        log.warn("模型周期评审失败 appId={} horizonDays={} code={}", app, horizon, problem.code());
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("模型周期评审跳过 appId={}", app, failure);
            }
        }
    }
}
