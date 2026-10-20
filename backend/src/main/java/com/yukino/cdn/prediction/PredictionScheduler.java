package com.yukino.cdn.prediction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.prediction", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class PredictionScheduler {
    private static final Logger log = LoggerFactory.getLogger(PredictionScheduler.class);
    private final PredictionWorker worker;
    private boolean storageWarning;

    public PredictionScheduler(PredictionWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${app.prediction.worker-tick-ms:500}")
    public void tick() {
        try {
            worker.tick();
            storageWarning = false;
        } catch (DataAccessException exception) {
            if (!storageWarning) log.warn("预测任务存储不可用；请检查 MySQL 连接和已审批的增量表变更。调度器稍后重试。");
            storageWarning = true;
        } catch (RuntimeException exception) {
            // Request and deadline remain durable; the next tick or process restart can recover them.
            log.error("预测编排暂时中断，将在总时限内重试持久化任务。", exception);
        }
    }
}
