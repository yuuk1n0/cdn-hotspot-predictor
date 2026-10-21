package com.yukino.cdn.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.prediction.PredictionService;
import com.yukino.cdn.prediction.PredictionProblem;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.yukino.cdn.operations.OperationsSupport.*;

@Service
public class DefaultOperationsService implements OperationsService {
    private final OperationsRepository store;
    private final PredictionService predictions;
    private final ModelEvidenceGateway models;
    private final Clock clock;
    private final ObjectMapper mapper;
    public DefaultOperationsService(OperationsRepository store, PredictionService predictions, ModelEvidenceGateway models, Clock clock, ObjectMapper mapper) {
        this.store = store; this.predictions = predictions; this.models = models; this.clock = clock; this.mapper = mapper;
    }
    private ObjectNode object() { return mapper.createObjectNode(); }
    public List<String> configuredApps() { return store.apps(); }
    public List<String> reminderRuns(String app) {
        return predictions.published(id(app), null, null, 1).stream()
                .filter(r -> "REAL".equals(r.path("datasetSource").asText()) && "MODEL".equals(r.path("predictionSource").asText()))
                .map(r -> r.path("runId").asText()).toList();
    }
    public ObjectNode config(String app) {
        id(app);
        var stored = store.config(app);
        var result = stored == null ? object().put("appId", app).put("version", 0).put("notificationsEnabled", false).put("destinationId", "")
                .put("handlingHours", 24).put("modelReviewEnabled", false).putNull("latestSuggestion") : stored.deepCopy();
        var available = result.putArray("availableModels");
        try {
            var evidence = models.metrics(app, 1);
            var successful = new HashSet<String>();
            predictions.published(app, null, null, 0).forEach(r -> successful.add(r.path("modelVersion").asText() + ":" + r.path("predictionSource").asText()));
            for (var model : evidence.path("availableModels"))
                if (successful.contains(model.path("modelVersion").asText() + ":" + model.path("predictionSource").asText())) available.add(model);
        } catch (PredictionProblem unavailable) { result.putArray("dependencyReasonCodes").add(unavailable.code()); }
        String initial = available.isEmpty() ? "" : available.get(0).path("modelVersion").asText();
        if (!result.has("dailyModelVersion")) result.put("dailyModelVersion", initial);
        if (!result.has("weeklyModelVersion")) result.put("weeklyModelVersion", initial);
        return result;
    }
    public ObjectNode updateConfig(String app, JsonNode body) {
        id(app);
        if (!body.isObject() || !body.path("expectedVersion").isIntegralNumber() || !body.path("expectedVersion").canConvertToInt() || body.path("expectedVersion").asInt() < 0)
            throw problem(422, "INVALID_CONFIG", "须提供expectedVersion整数。");
        Set<String> allowed = Set.of("expectedVersion", "notificationsEnabled", "destinationId", "handlingHours", "modelReviewEnabled");
        body.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw problem(422, "INVALID_CONFIG", "配置字段不允许修改；模型版本须独立确认。"); });
        ObjectNode result = config(app);
        if (result.path("version").asInt() != body.path("expectedVersion").asInt()) throw problem(409, "VERSION_CONFLICT", "配置已变化，请刷新。");
        for (String name : List.of("notificationsEnabled", "modelReviewEnabled")) if (body.has(name)) {
            if (!body.path(name).isBoolean()) throw problem(422, "INVALID_CONFIG", name + "须为boolean。"); result.set(name, body.get(name));
        }
        if (body.has("handlingHours")) {
            JsonNode hours = body.path("handlingHours");
            if (!hours.isIntegralNumber() || !hours.canConvertToInt() || hours.intValue() < 1 || hours.intValue() > 168) throw problem(422, "INVALID_CONFIG", "处理时限须为1至168小时。");
            result.set("handlingHours", hours);
        }
        if (body.has("destinationId")) {
            if (!body.path("destinationId").isTextual() || !body.path("destinationId").asText().matches("[A-Za-z0-9_-]{0,128}")) throw problem(422, "INVALID_CONFIG", "目的地ID不合法。");
            result.set("destinationId", body.get("destinationId"));
        }
        if (result.path("notificationsEnabled").asBoolean() && result.path("destinationId").asText().isBlank()) throw problem(422, "DESTINATION_REQUIRED", "启用通知须先选择目的地。");
        return persistConfig(app, result, body.path("expectedVersion").asInt());
    }
    private ObjectNode persistConfig(String app, ObjectNode result, int version) {
        ObjectNode stored = result.deepCopy(); stored.remove(List.of("availableModels", "dependencyReasonCodes"));
        ObjectNode saved = store.saveConfig(app, stored, version, clock.instant());
        if (result.has("availableModels")) saved.set("availableModels", result.get("availableModels"));
        return saved;
    }
    public ObjectNode run(String app, String runId, LocalDate date, int horizon) {
        id(app);
        if (horizon != 0 && horizon != 1 && horizon != 7) throw problem(422, "INVALID_HORIZON", "跨度须为1或7。");
        if (runId != null && (runId.isBlank() || runId.length() > 128)) throw problem(422, "INVALID_RUN_ID", "运行编号不合法。");
        var values = predictions.published(app, runId, date, horizon);
        if (values.isEmpty()) throw problem(404, runId == null ? "NO_CURRENT_RUN" : "RUN_CONTEXT_MISMATCH", "没有匹配App、日期和跨度的成功运行；未使用旧运行代替。");
        ObjectNode result = values.get(0).deepCopy();
        if (!app.equals(result.path("appId").asText()) || (date != null && !date.toString().equals(result.path("predictionStartDate").asText()))
                || (horizon != 0 && result.path("horizonDays").asInt() != horizon)) throw problem(422, "RUN_CONTEXT_MISMATCH", "运行上下文不匹配。");
        result.put("businessDate", result.path("predictionStartDate").asText());
        return result;
    }
    public ObjectNode runList(String app, LocalDate date, int horizon) {
        id(app);
        if (horizon != 1 && horizon != 7) throw problem(422, "INVALID_HORIZON", "跨度须为1或7。");
        var result = object().put("appId", app); var items = result.putArray("items");
        predictions.published(app, null, date, horizon).forEach(run -> {
            var summary = run.deepCopy(); summary.remove("items"); items.add(summary);
        }); return result;
    }
    public ObjectNode pending(String app, String runId, String evidenceSource) {
        source(evidenceSource);
        var result = run(app, runId, null, 0);
        if (evidenceSource.equals("REAL") && !result.path("datasetSource").asText().equals("REAL")) throw problem(422, "FILE_SOURCE_NOT_REAL", "FAKE运行只能使用TEST处理证据。");
        Map<String, JsonNode> states = new HashMap<>();
        for (var record : store.records(app, evidenceSource)) states.put(record.path("fileKey").asText(), record);
        var original = result.path("items").deepCopy(); var items = result.putArray("items");
        for (var value : original) {
            JsonNode state = states.get(value.path("fileKey").asText());
            String status = state == null ? "PENDING" : state.path("status").asText();
            if (!status.equals("COMPRESSED")) items.add(((ObjectNode) value).deepCopy().put("compressionStatus", status).put("evidenceSource", evidenceSource));
        }
        return result.put("evidenceSource", evidenceSource).put("itemCount", items.size()).put("statusBasis", "REGISTERED_HANDLING_STATE");
    }
    public ObjectNode overdue(String app, String runId, String evidenceSource) {
        var result = pending(app, runId, evidenceSource);
        var configured = store.config(app); int hours = configured == null ? 24 : configured.path("handlingHours").asInt(24);
        Instant due;
        try { due = Instant.parse(result.path("publishedAt").asText()).plus(Duration.ofHours(hours)); }
        catch (RuntimeException invalid) { throw problem(503, "PUBLISHED_TIME_UNAVAILABLE", "无法核对Java发布时间，不推定处理截止时间。"); }
        if (!clock.instant().isAfter(due)) result.putArray("items");
        for (var item : result.path("items")) ((ObjectNode) item).put("dueAt", due.toString());
        return result.put("asOf", clock.instant().toString()).put("handlingHours", hours).put("itemCount", result.path("items").size());
    }
    public ObjectNode effects(String app, String evidenceSource) {
        id(app); source(evidenceSource);
        var result = object().put("appId", app).put("evidenceSource", evidenceSource).put("isRealizedBillSaving", false).put("queriedAt", clock.instant().toString());
        var items = result.putArray("items"); int observations = 0;
        for (var record : store.records(app, evidenceSource)) {
            items.add(record); observations += record.path("observations").size();
        }
        result.put("recordCount", items.size()).put("observationCount", observations);
        var reasons = result.putArray("reasonCodes"); if (observations == 0) reasons.add("NO_COMPRESSION_OBSERVATION");
        result.putNull("realizedBillSavingYuan"); return result;
    }
    public ObjectNode suggestion(String app, int horizon, String backtestId) {
        id(app);
        if (horizon != 1 && horizon != 7) throw problem(422, "INVALID_HORIZON", "跨度须为1或7。");
        var configuration = config(app);
        var suggestion = object().put("suggestionId", "sug_" + UUID.randomUUID()).put("appId", app).put("horizonDays", horizon)
                .put("createdAt", clock.instant().toString()).put("canSwitch", false).putNull("targetModelVersion");
        var reasons = suggestion.putArray("reasonCodes");
        try {
            var evidence = models.metrics(app, horizon); suggestion.set("evidence", evidence);
            if (evidence.path("availableModels").size() < 2) reasons.add("MODEL_SWITCH_NOT_AVAILABLE");
            reasons.add("UNCONFIRMED_LABELS").add("NO_CONFIRMED_COMPARABLE_BACKTEST");
        } catch (PredictionProblem unavailable) { reasons.add(unavailable.code()); suggestion.putNull("evidence"); }
        if (backtestId != null && !backtestId.isBlank()) reasons.add("BACKTEST_API_NOT_AVAILABLE");
        suggestion.putArray("recommendations").add("补齐同一App与跨度的成熟观测及完整标签证明")
                .add("至少两个实际部署并验证的真实模型在相同成熟截止日上比较，当前不自动更换版本");
        configuration.set("latestSuggestion", suggestion);
        if (!configuration.path("latestSuggestions").isObject()) configuration.putObject("latestSuggestions");
        ((ObjectNode) configuration.path("latestSuggestions")).set(Integer.toString(horizon), suggestion);
        persistConfig(app, configuration, configuration.path("version").asInt());
        return suggestion;
    }
    public ObjectNode switchModel(String app, JsonNode body) {
        var configuration = config(id(app));
        if (!body.path("confirmed").isBoolean() || !body.path("confirmed").booleanValue()) throw problem(422, "CONFIRMATION_REQUIRED", "模型切换必须独立确认。");
        if (!body.path("expectedVersion").isIntegralNumber() || body.path("expectedVersion").asInt() != configuration.path("version").asInt()) throw problem(409, "VERSION_CONFLICT", "配置已变化，请重新查看建议。");
        String requested = text(body, "suggestionId", 128); boolean owned = false;
        for (var suggestion : configuration.path("latestSuggestions")) if (requested.equals(suggestion.path("suggestionId").asText())) owned = true;
        if (!owned) throw problem(409, "SUGGESTION_EXPIRED", "建议不属于此App或已过期。");
        throw problem(409, "MODEL_SWITCH_NOT_AVAILABLE", "当前没有两个已部署模型及合格可比评估，配置未改变。");
    }
    public ObjectNode evidence(String tool, JsonNode context, JsonNode arguments) {
        String app = id(text(context, "appId", 32));
        if (!arguments.isObject()) throw problem(422, "INVALID_TOOL_ARGUMENTS", "工具参数须为对象。");
        Set<String> allowed = Set.of("appId", "businessDate", "horizonDays", "runId", "fileKey", "backtestId");
        arguments.fields().forEachRemaining(field -> {
            if (!allowed.contains(field.getKey())) throw problem(422, "INVALID_TOOL_ARGUMENTS", "工具参数不在白名单。");
            if (Set.of("appId", "businessDate", "horizonDays", "runId").contains(field.getKey()) && !field.getValue().equals(context.path(field.getKey())))
                throw problem(422, "TOOL_CONTEXT_MISMATCH", "工具不能改变App、日期、跨度或所选运行。");
            if (Set.of("fileKey", "backtestId").contains(field.getKey()) && context.hasNonNull(field.getKey()) && !context.path(field.getKey()).equals(field.getValue()))
                throw problem(422, "TOOL_CONTEXT_MISMATCH", "工具不能改变已选文件或报告。");
        });
        LocalDate date;
        try { date = LocalDate.parse(text(context, "businessDate", 10)); }
        catch (java.time.format.DateTimeParseException invalid) { throw problem(422, "INVALID_DATE", "业务日期无效。"); }
        int horizon = context.path("horizonDays").asInt();
        if (horizon != 1 && horizon != 7) throw problem(422, "INVALID_HORIZON", "跨度须为1或7。");
        String runId = context.hasNonNull("runId") && !context.path("runId").asText().isBlank() ? context.path("runId").asText() : null;
        ObjectNode data;
        switch (tool) {
            case "getTopHotspots" -> {
                data = run(app, runId, date, horizon); var rows = data.path("items").deepCopy(); var top = data.putArray("items");
                for (int i = 0; i < Math.min(10, rows.size()); i++) top.add(rows.get(i));
                data.put("scope", "PUBLISHED_TOP50_FIRST10");
            }
            case "explainFile" -> {
                var selected = run(app, runId, date, horizon);
                String key = arguments.hasNonNull("fileKey") ? text(arguments, "fileKey", 4096) : text(context, "fileKey", 4096);
                data = selected.deepCopy(); data.remove("items"); JsonNode found = null;
                for (var row : selected.path("items")) if (key.equals(row.path("fileKey").asText())) found = row;
                if (found == null) throw problem(404, "FILE_NOT_IN_PUBLISHED_TOP50", "文件不在已发布Top50中，无法推定其预测为0。");
                data.set("file", found); data.put("predictionBasis", "WINDOW_CUMULATIVE_BYTES");
            }
            case "getCompressionEffects" -> data = effects(app, "REAL");
            case "getModelMetrics" -> {
                data = models.metrics(app, horizon); data.put("requestedBusinessDate", date.toString());
                data.put("isDailyBacktestForRequestedDate", false);
            }
            case "getOverdueFiles" -> {
                var selected = run(app, runId, date, horizon); data = overdue(app, selected.path("runId").asText(), "REAL");
            }
            case "getModelSuggestion" -> data = suggestion(app, horizon, arguments.hasNonNull("backtestId") ? arguments.path("backtestId").asText() : null);
            default -> throw problem(422, "UNKNOWN_TOOL", "工具不在只读白名单中。");
        }
        var result = object().put("tool", tool).put("appId", app).put("queriedAt", clock.instant().toString());
        result.set("context", context.deepCopy()); result.set("data", data); result.set("reasonCodes", data.has("reasonCodes") ? data.path("reasonCodes").deepCopy() : mapper.createArrayNode());
        return result;
    }
}
