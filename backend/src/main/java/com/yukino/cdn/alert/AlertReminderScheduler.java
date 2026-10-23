package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class AlertReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(AlertReminderScheduler.class);
    private final AlertIncidentRepository incidents;
    private final AlertRouteService routes;
    private final AlertDispatcher dispatcher;
    private final Clock clock;

    public AlertReminderScheduler(AlertIncidentRepository incidents, AlertRouteService routes,
                                  AlertDispatcher dispatcher, Clock clock) {
        this.incidents = incidents;
        this.routes = routes;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ALERT_REMINDER_POLL_MS:60000}")
    public void scheduledTick() {
        try { tick(); }
        catch (RuntimeException error) { log.warn("Alert reminder scan failed: {}", error.getClass().getSimpleName()); }
    }

    public void tick() {
        var now = clock.instant();
        for (ObjectNode incident : incidents.dueReminders(now)) {
            try {
                ObjectNode route = routes.get(incident.path("appId").asText());
                if (!route.path("alertsEnabled").asBoolean()) continue;
                int maximum = route.path("maxReminderCount").asInt(4);
                if (incident.path("reminderCount").asInt() >= maximum) {
                    incidents.stopReminder(incident.path("incidentId").asText(),
                            incident.path("version").asInt());
                    continue;
                }
                int interval = Math.max(1, route.path("remindIntervalMinutes").asInt(15));
                var next = now.plus(Duration.ofMinutes(interval));
                if (!incidents.claimReminder(incident.path("incidentId").asText(),
                        incident.path("version").asInt(), next, now)) continue;
                dispatcher.reminder(incidents.get(incident.path("incidentId").asText()), route);
            } catch (RuntimeException error) {
                log.warn("Alert reminder skipped incidentId={} code={}",
                        incident.path("incidentId").asText(), error.getClass().getSimpleName());
            }
        }
    }
}
