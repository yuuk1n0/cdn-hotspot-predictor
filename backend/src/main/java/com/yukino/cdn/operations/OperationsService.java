package com.yukino.cdn.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;

/** Shared business contract. Clients never read another module's persistence directly. */
public interface OperationsService {
    ObjectNode config(String appId);
    ObjectNode updateConfig(String appId, JsonNode body);
    List<String> configuredApps();
    default List<String> reminderRuns(String appId) { return List.of(); }
    ObjectNode run(String appId, String runId, LocalDate businessDate, int horizonDays);
    ObjectNode pending(String appId, String runId, String evidenceSource);
    ObjectNode overdue(String appId, String runId, String evidenceSource);
    ObjectNode evidence(String tool, JsonNode context, JsonNode arguments);
    ObjectNode suggestion(String appId, int horizonDays, String backtestId);
    default ObjectNode switchModel(String appId, JsonNode body) { throw new UnsupportedOperationException("模型切换服务未实现。"); }
}
