package com.yukino.cdn.agent;

public final class AgentProblem extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public AgentProblem(int httpStatus, String code, String safeMessage) {
        super(safeMessage);
        this.httpStatus = httpStatus;
        this.code = code;
    }
    public int httpStatus() { return httpStatus; }
    public String code() { return code; }
}
