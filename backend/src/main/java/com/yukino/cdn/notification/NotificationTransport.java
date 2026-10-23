package com.yukino.cdn.notification;

public interface NotificationTransport {
    Outcome send(NotificationSettings.Destination destination, String text);

    record Outcome(String status, String code, boolean retryable) {
        public static Outcome sent() { return new Outcome("SENT", null, false); }
        public static Outcome failed(String code) { return new Outcome("FAILED", code, false); }
        public static Outcome retryable(String code) { return new Outcome("FAILED", code, true); }
        public static Outcome unknown(String code) { return new Outcome("UNKNOWN", code, false); }
    }
}
