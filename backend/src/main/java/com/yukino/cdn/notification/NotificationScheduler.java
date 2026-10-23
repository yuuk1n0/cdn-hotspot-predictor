package com.yukino.cdn.notification;

import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class NotificationScheduler {
    private final NotificationService service;
    public NotificationScheduler(NotificationService service) { this.service = service; }
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
    void daily() { safe(() -> service.automatic("DAILY")); }
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Shanghai")
    void overdue() { safe(() -> service.automatic("OVERDUE")); }
    @Scheduled(fixedDelayString = "${NOTIFICATION_POLL_MS:30000}")
    void sendDue() { safe(service::processDue); }
    private void safe(Runnable action) {
        try { action.run(); } catch (DataAccessException ignored) {
            // A pending migration must not prevent other application features from starting.
        }
    }
}
