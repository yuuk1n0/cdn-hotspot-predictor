package com.yukino.cdn.prediction;

public final class PredictionProblem extends RuntimeException {
    private final int httpStatus;
    private final String code;
    private final boolean retryable;

    public PredictionProblem(int status, String code, String message) {
        this(status, code, message, false);
    }

    private PredictionProblem(int status, String code, String message, boolean retryable) {
        super(message);
        this.httpStatus = status;
        this.code = code;
        this.retryable = retryable;
    }

    public static PredictionProblem retryable(String code, String message) {
        return new PredictionProblem(503, code, message, true);
    }

    public int httpStatus() { return httpStatus; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public Error error() { return new Error(code, getMessage()); }

    public record Error(String code, String message) {
        public Error {
            code = code == null ? "PYTHON_ERROR" : code.substring(0, Math.min(64, code.length()));
            message = message == null ? "依赖服务返回错误。" : message.substring(0, Math.min(512, message.length()));
        }
    }
}
