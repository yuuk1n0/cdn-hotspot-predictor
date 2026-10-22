package com.yukino.cdn.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsService;
import com.yukino.cdn.prediction.PredictionProblem;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only the six read-only business functions below are callable by the model. */
public final class AgentService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> TOOLS = Set.of("getTopHotspots", "explainFile", "getCompressionEffects",
            "getModelMetrics", "getOverdueFiles", "getModelSuggestion");
    private static final Set<String> ARGUMENTS = Set.of("appId", "businessDate", "horizonDays", "runId", "fileKey", "backtestId");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d])(?:\\d+(?:\\.\\d+)?)(?:\\s*(?:%|[KMGTP]?B|元))?");
    private static final ExecutorService DEADLINE_WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private final OperationsService operations;
    private final AgentModel model;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AgentSettings settings;
    private final Duration limit;

    public AgentService(OperationsService operations, AgentModel model, ObjectMapper mapper, Clock clock,
                        AgentSettings settings, Duration limit) {
        this.operations = operations;
        this.model = model;
        this.mapper = mapper;
        this.clock = clock;
        this.settings = settings;
        this.limit = limit;
    }

    public ObjectNode status() {
        return mapper.createObjectNode().put("configured", settings.configured())
                .put("model", settings.configured() ? settings.model() : "")
                .put("maxModelRequests", 4);
    }

    public ObjectNode chat(JsonNode request) {
        ObjectNode context = context(request);
        String question = request.path("message").asText("").trim();
        if (question.isEmpty() || question.length() > 4000)
            throw new AgentProblem(422, "AGENT_INVALID_REQUEST", "message 必须是非空且不超过4000字的文本。");
        boolean top5Pending = top5PendingQuestion(question);
        if (top5Pending) return top5PendingTemplate(context);
        if (top10HotspotQuestion(question)) return top10HotspotTemplate(context);
        if (overdueQuestion(question)) return overdueTemplate(context);
        if (modelSuggestionQuestion(question)) return modelSuggestionTemplate(context);
        if (!settings.configured()) {
            throw new AgentProblem(503, "AGENT_NOT_CONFIGURED", "Agent 模型服务未配置。");
        }
        long deadline = System.nanoTime() + limit.toNanos();
        ArrayNode messages = mapper.createArrayNode();
        messages.add(mapper.createObjectNode().put("role", "system").put("content",
                "你只能依据工具提供的实时事实回答。需要业务事实时必须先调用所列工具；无数据时明确未知。"+
                "引用数据日期、App、跨度、运行与来源。不要臆造金额、百分比、排名或模型效果。今天/昨天按实际上海日期理解。"));
        messages.add(mapper.createObjectNode().put("role", "user").put("content", question));
        ArrayNode evidence = mapper.createArrayNode();
        ArrayNode uncertainties = mapper.createArrayNode();
        int noToolAnswers = 0;
        try {
        for (int round = 0; round < 4; round++) {
            ObjectNode body = mapper.createObjectNode().put("model", settings.model()).put("tool_choice", "auto");
            body.set("messages", messages.deepCopy());
            body.set("tools", toolDefinitions());
            JsonNode result = bounded(() -> model.complete(body, remaining(deadline)), deadline, true);
            if (result == null) throw new AgentProblem(503, "AGENT_MODEL_UNAVAILABLE", "模型响应格式无效。");
            JsonNode reply = result.path("choices").path(0).path("message");
            if (!reply.isObject()) throw new AgentProblem(503, "AGENT_MODEL_UNAVAILABLE", "模型响应格式无效。");
            JsonNode calls = reply.path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                if (calls.size() > 3) {
                    uncertainties.add("单轮工具调用超过3个，已拒绝执行。");
                    return response("无法确认业务事实。", context, evidence, uncertainties, "INSUFFICIENT_EVIDENCE");
                }
                for (JsonNode call : calls) {
                    JsonNode args = parseArguments(call);
                    if (call.path("id").asText("").isBlank() || !validArguments(call.path("function").path("name").asText(""), args, context)) {
                        uncertainties.add("模型工具调用不在授权范围或参数与当前上下文冲突，已拒绝执行。");
                        return response("无法确认业务事实。", context, evidence, uncertainties, "INSUFFICIENT_EVIDENCE");
                    }
                }
                ObjectNode assistant = mapper.createObjectNode().put("role", "assistant");
                assistant.set("content", reply.has("content") ? reply.get("content") : mapper.getNodeFactory().nullNode());
                assistant.set("tool_calls", calls.deepCopy());
                messages.add(assistant);
                for (JsonNode call : calls) {
                    String name = call.path("function").path("name").asText("");
                    String id = call.path("id").asText("");
                    JsonNode args = parseArguments(call);
                    final JsonNode validated = args;
                    ObjectNode item;
                    try {
                        item = bounded(() -> operations.evidence(name, context.deepCopy(), validated), deadline, false);
                    } catch (AgentProblem problem) {
                        if (problem.httpStatus() == 504) throw problem;
                        uncertainties.add("业务工具暂时不可用：" + name);
                        return response("无法确认业务事实。", context, evidence, uncertainties, "INSUFFICIENT_EVIDENCE");
                    }
                    if (!validEvidence(name, item, context)) {
                        uncertainties.add("业务工具未返回与所选 App、日期、跨度和运行一致的数据：" + name);
                        return response("无法确认业务事实。", context, evidence, uncertainties, "INSUFFICIENT_EVIDENCE");
                    }
                    evidence.add(item.deepCopy());
                    JsonNode reasons = item.path("reasonCodes");
                    if (reasons.isArray()) for (JsonNode reason : reasons)
                        uncertainties.add("业务证据说明：" + reason.asText("UNKNOWN"));
                    messages.add(mapper.createObjectNode().put("role", "tool").put("tool_call_id", id)
                            .put("content", item.toString()));
                }
                continue;
            }
            String answer = reply.path("content").asText("").trim();
            if (evidence.isEmpty()) {
                if (++noToolAnswers < 2 && round < 3) {
                    messages.add(mapper.createObjectNode().put("role", "assistant").put("content", answer));
                    messages.add(mapper.createObjectNode().put("role", "system").put("content", "尚未调用工具，必须先调用相关工具，不能直接回答业务事实。"));
                    continue;
                }
                uncertainties.add("模型未调用业务工具，没有可核验的实时证据。");
                return response("无法确认业务事实。", context, evidence, uncertainties, "INSUFFICIENT_EVIDENCE");
            }
            if (answer.isEmpty() || unsupportedNumbers(answer, evidence)) {
                uncertainties.add("模型回答中的数字缺少对应工具证据，已改为原始事实摘要。");
                return response(factSummary(evidence), context, evidence, uncertainties, "FACT_SUMMARY");
            }
            return response(answer, context, evidence, uncertainties, "GROUNDED");
        }
        uncertainties.add("模型达到4轮请求上限，未得到可发布回答。");
        return response("无法确认业务事实。", context, evidence, uncertainties, "INSUFFICIENT_EVIDENCE");
        } catch (AgentProblem problem) {
            if (top5Pending && Set.of("AGENT_MODEL_UNAVAILABLE", "AGENT_TIMEOUT", "AGENT_NOT_CONFIGURED").contains(problem.code()))
                return top5PendingTemplate(context);
            throw problem;
        }
    }

    private boolean top5PendingQuestion(String question) {
        String text = question.toLowerCase().replace(" ", "");
        boolean top = text.contains("top5") || text.contains("前5") || text.contains("前五");
        boolean compression = text.contains("压缩") || text.contains("待处理");
        return top && compression;
    }

    private boolean top10HotspotQuestion(String question) {
        String text = question.toLowerCase().replace(" ", "");
        boolean top = text.contains("top10") || text.contains("前10") || text.contains("前十");
        return top && (text.contains("热点") || text.contains("文件"));
    }

    private boolean overdueQuestion(String question) {
        String text = question.toLowerCase().replace(" ", "");
        return text.contains("逾期") || text.contains("超时");
    }

    private boolean modelSuggestionQuestion(String question) {
        String text = question.toLowerCase().replace(" ", "");
        boolean subject = text.contains("模型") || text.contains("算法") || text.contains("预测参数");
        boolean asks = text.contains("建议") || text.contains("改进") || text.contains("优化")
                || text.contains("合理") || text.contains("替换") || text.contains("退化")
                || text.contains("评估") || text.contains("回测") || text.contains("切换");
        return subject && asks;
    }

    /** Deterministic read-only answer for the core question; it never estimates benefit or invents compression state. */
    private ObjectNode top5PendingTemplate(ObjectNode context) {
        ArrayNode evidence = mapper.createArrayNode();
        ArrayNode uncertainties = mapper.createArrayNode().add("核心问题使用只读业务事实模板；未推测压缩收益或质量。");
        try {
            ObjectNode hotspots = operations.evidence("getTopHotspots", context.deepCopy(), mapper.createObjectNode());
            evidence.add(hotspots.deepCopy());
            String appId = context.path("appId").asText();
            String runId = hotspots.path("context").path("runId").asText();
            if (runId.isBlank()) throw new AgentProblem(404, "NO_CURRENT_RUN", "没有可用的最新成功预测运行。");
            ObjectNode pending = operations.pending(appId, runId, "REAL");
            ObjectNode pendingEvidence = mapper.createObjectNode().put("tool", "getPendingFiles").put("appId", appId);
            pendingEvidence.set("context", hotspots.path("context").deepCopy());
            pendingEvidence.set("data", pending);
            evidence.add(pendingEvidence);
            String start = hotspots.path("data").path("predictionStartDate").asText();
            String end = hotspots.path("data").path("predictionEndDate").asText();
            String model = hotspots.path("data").path("modelVersion").asText();
            String source = hotspots.path("data").path("datasetSource").asText() + "/" + hotspots.path("data").path("predictionSource").asText();
            ObjectNode responseContext = runContext(context, hotspots.path("data"), runId);
            StringBuilder answer = new StringBuilder("当前 App ").append(appId)
                    .append(" 最新成功预测 runId=").append(runId)
                    .append("，数据截止 ").append(hotspots.path("data").path("cutoffDate").asText())
                    .append("，窗口 ").append(start).append(" 至 ").append(end)
                    .append("，模型 ").append(model).append("，来源 ").append(source).append("。");
            JsonNode items = pending.path("items");
            int count = Math.min(5, items.size());
            if (count == 0) {
                answer.append("当前运行时点没有登记为待压缩的文件。");
            } else {
                answer.append("待压缩 Top").append(count).append("：");
                for (int index = 0; index < count; index++) {
                    JsonNode item = items.get(index);
                    if (index > 0) answer.append("；");
                    answer.append(index + 1).append(". ").append(item.path("fileKey").asText())
                            .append("，预测窗口累计 ").append(item.path("predictedBytes").asText()).append(" B")
                            .append("，状态 ").append(item.path("compressionStatus").asText("PENDING"));
                }
                answer.append("。");
            }
            return response(answer.toString(), responseContext, evidence, uncertainties, "DETERMINISTIC_BUSINESS_FACT");
        } catch (PredictionProblem problem) {
            throw new AgentProblem(problem.httpStatus(), problem.code(), problem.getMessage());
        }
    }

    private ObjectNode top10HotspotTemplate(ObjectNode context) {
        ArrayNode evidence = mapper.createArrayNode();
        ArrayNode uncertainties = mapper.createArrayNode().add("Top10 为预测窗口累计流量，不是已发生流量；未校准完整流量。");
        try {
            ObjectNode hotspots = operations.evidence("getTopHotspots", context.deepCopy(), mapper.createObjectNode());
            evidence.add(hotspots.deepCopy());
            JsonNode data = hotspots.path("data");
            String runId = hotspots.path("context").path("runId").asText();
            if (runId.isBlank()) throw new AgentProblem(404, "NO_CURRENT_RUN", "没有可用的最新成功预测运行。");
            String appId = context.path("appId").asText();
            String start = data.path("predictionStartDate").asText();
            String end = data.path("predictionEndDate").asText();
            ObjectNode responseContext = runContext(context, data, runId);
            StringBuilder answer = new StringBuilder("当前 App ").append(appId)
                    .append(" 最新成功预测 runId=").append(runId)
                    .append("，数据截止 ").append(data.path("cutoffDate").asText())
                    .append("，窗口 ").append(start).append(" 至 ").append(end)
                    .append("，模型 ").append(data.path("modelVersion").asText())
                    .append("，来源 ").append(data.path("datasetSource").asText()).append("/")
                    .append(data.path("predictionSource").asText()).append("。");
            JsonNode items = data.path("items");
            int count = Math.min(10, items.size());
            if (count == 0) {
                answer.append("当前运行没有已发布的热点文件。");
            } else {
                answer.append("Top").append(count).append(" 热点：");
                for (int index = 0; index < count; index++) {
                    JsonNode item = items.get(index);
                    if (index > 0) answer.append("；");
                    answer.append(item.path("rank").asInt(index + 1)).append(". ")
                            .append(item.path("fileKey").asText())
                            .append("，预测窗口累计 ").append(item.path("predictedBytes").asText()).append(" B");
                }
                answer.append("。");
            }
            return response(answer.toString(), responseContext, evidence, uncertainties, "DETERMINISTIC_BUSINESS_FACT");
        } catch (PredictionProblem problem) {
            throw new AgentProblem(problem.httpStatus(), problem.code(), problem.getMessage());
        }
    }

    private ObjectNode overdueTemplate(ObjectNode context) {
        ArrayNode evidence = mapper.createArrayNode();
        ArrayNode uncertainties = mapper.createArrayNode().add("逾期清单只基于已发布的真实预测和登记状态，不推定未登记文件。");
        try {
            ObjectNode overdueEvidence = operations.evidence("getOverdueFiles", context.deepCopy(), mapper.createObjectNode());
            evidence.add(overdueEvidence.deepCopy());
            JsonNode data = overdueEvidence.path("data");
            String runId = overdueEvidence.path("context").path("runId").asText();
            if (runId.isBlank()) throw new AgentProblem(404, "NO_CURRENT_RUN", "没有可用的最新成功预测运行。");
            ObjectNode responseContext = runContext(context, data, runId);
            JsonNode items = data.path("items");
            int count = items.size();
            int shown = Math.min(10, count);
            StringBuilder answer = new StringBuilder("当前 App ").append(context.path("appId").asText())
                    .append(" 运行 ").append(runId)
                    .append(" 截至 ").append(data.path("asOf").asText("服务端查询时间"))
                    .append("，处理时限 ").append(data.path("handlingHours").asInt()).append(" 小时，")
                    .append(count == 0 ? "没有逾期未处理文件。" : "共有 " + count + " 个逾期未处理文件。");
            if (shown > 0) {
                answer.append("前 ").append(shown).append(" 项：");
                for (int index = 0; index < shown; index++) {
                    JsonNode item = items.get(index);
                    if (index > 0) answer.append("；");
                    answer.append(item.path("rank").asInt(index + 1)).append(". ")
                            .append(item.path("fileKey").asText())
                            .append("，状态 ").append(item.path("compressionStatus").asText("PENDING"))
                            .append("，应处理时间 ").append(item.path("dueAt").asText("未提供"));
                }
                answer.append("。");
                if (count > shown) uncertainties.add("页面仅列出前 " + shown + " 项，完整清单见证据。");
            }
            return response(answer.toString(), responseContext, evidence, uncertainties, "DETERMINISTIC_BUSINESS_FACT");
        } catch (PredictionProblem problem) {
            throw new AgentProblem(problem.httpStatus(), problem.code(), problem.getMessage());
        }
    }

    /** Deterministic model-review answer; no model call is required and no result is fabricated. */
    private ObjectNode modelSuggestionTemplate(ObjectNode context) {
        ArrayNode evidence = mapper.createArrayNode();
        ArrayNode uncertainties = mapper.createArrayNode()
                .add("模型建议使用已观测文件未来窗口的代理指标，不代表完整 CDN 流量准确率。")
                .add("参数替换仅在同一 App、同一跨度、同一评估窗口内成立，并须人工确认。");
        try {
            String appId = context.path("appId").asText();
            int horizon = context.path("horizonDays").asInt();
            ObjectNode suggestion = operations.suggestion(appId, horizon, null);
            ObjectNode item = mapper.createObjectNode().put("tool", "getModelSuggestion").put("appId", appId)
                    .put("queriedAt", clock.instant().toString());
            item.set("context", context.deepCopy());
            item.set("data", suggestion);
            item.putArray("reasonCodes").addAll((ArrayNode) suggestion.path("reasonCodes").deepCopy());
            evidence.add(item);

            String currentMethod = suggestion.path("currentMethod").asText(suggestion.path("evidence").path("selectedMethod").asText("未提供"));
            JsonNode currentSpec = suggestion.has("currentSpec") ? suggestion.path("currentSpec") : suggestion.path("evidence").path("selectedSpec");
            JsonNode metrics = suggestion.has("currentMetrics") ? suggestion.path("currentMetrics") : suggestion.path("evidence").path("proxyMetrics");
            String windowStart = suggestion.path("cutoffStartDate").asText(suggestion.path("evidence").path("evidenceStartDate").asText("未提供"));
            String windowEnd = suggestion.path("cutoffEndDate").asText(suggestion.path("evidence").path("evidenceEndDate").asText("未提供"));
            int cutoffs = suggestion.path("cutoffCount").asInt(suggestion.path("evidence").path("report").path("testCutoffCount").asInt());
            StringBuilder answer = new StringBuilder("当前 App ").append(appId).append(" · ")
                    .append(horizon == 7 ? "WEEK（未来7天累计）" : "DAY（下一自然日）")
                    .append("。部署模型 ").append(suggestion.path("modelVersion").asText(suggestion.path("evidence").path("deployedModelVersion").asText("hotspot-simple-v1")))
                    .append("，当前参数 ").append(currentMethod).append(' ').append(currentSpec.isObject() ? currentSpec.toString() : "未提供")
                    .append("。评估窗口 ").append(windowStart).append(" 至 ").append(windowEnd)
                    .append("，滚动切点 ").append(cutoffs).append(" 个；依据 MySQL 已观测文件的未来窗口代理指标。");
            if (metrics.isObject() && !metrics.isEmpty()) {
                answer.append(" 当前指标：Recall@50 ").append(percent(metrics.path("recallAt50").asDouble()))
                        .append("，NDCG@50 ").append(decimal(metrics.path("ndcgAt50").asDouble()))
                        .append("，观测流量覆盖 ").append(percent(metrics.path("observedTrafficCoverageAt50").asDouble())).append("。");
            } else {
                answer.append(" 当前指标未返回。");
            }
            JsonNode candidates = suggestion.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                answer.append(" 候选参数：");
                int shown = Math.min(4, candidates.size());
                for (int index = 0; index < shown; index++) {
                    JsonNode candidate = candidates.get(index);
                    if (index > 0) answer.append("；");
                    answer.append(candidate.path("name").asText(candidate.path("method").asText("candidate")))
                            .append(" Recall@50 ").append(percent(candidate.path("mean").path("recallAt50").asDouble()))
                            .append("，NDCG@50 ").append(decimal(candidate.path("mean").path("ndcgAt50").asDouble()));
                }
                answer.append("。");
            }
            boolean canSwitch = suggestion.path("canSwitch").asBoolean(false);
            if (canSwitch) {
                answer.append(" 结论：建议将本 App 的 ")
                        .append(horizon == 7 ? "周参数" : "日参数")
                        .append(" 替换为 ").append(suggestion.path("targetMethod").asText("目标参数"))
                        .append("（Recall 提升 ").append(percentagePoint(suggestion.path("improvementRecall").asDouble())).append("）。");
            } else {
                answer.append(" 结论：当前参数在非劣门槛内，暂不替换。");
            }
            JsonNode reasons = suggestion.path("reasonCodes");
            if (reasons.isArray() && !reasons.isEmpty()) answer.append(" 原因码：").append(reasons.toString()).append("。");
            answer.append(" 可切换：").append(canSwitch ? "是（需人工确认）" : "否")
                    .append("。来源：REAL/MODEL；指标口径：OBSERVED_FUTURE_PROXY。");
            ObjectNode responseContext = context.deepCopy().put("modelVersion", suggestion.path("modelVersion").asText("hotspot-simple-v1"))
                    .put("resultMode", "REAL/MODEL").put("metricBasis", suggestion.path("metricBasis").asText("OBSERVED_FUTURE_PROXY"));
            return response(answer.toString(), responseContext, evidence, uncertainties, "DETERMINISTIC_MODEL_REVIEW");
        } catch (PredictionProblem problem) {
            throw new AgentProblem(problem.httpStatus(), problem.code(), problem.getMessage());
        }
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String percentagePoint(double value) {
        return String.format(Locale.ROOT, "%.2f 个百分点", value * 100);
    }

    private ObjectNode runContext(ObjectNode context, JsonNode data, String runId) {
        return context.deepCopy().put("runId", runId)
                .put("modelVersion", data.path("modelVersion").asText())
                .put("predictionStartDate", data.path("predictionStartDate").asText())
                .put("predictionEndDate", data.path("predictionEndDate").asText())
                .put("resultMode", data.path("datasetSource").asText() + "/" + data.path("predictionSource").asText());
    }

    private ObjectNode context(JsonNode request) {
        if (request == null || !request.isObject()) throw invalid();
        String app = textValue(request, "appId");
        if (app == null || app.isBlank()) throw invalid();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        LocalDate day = today;
        String question = request.path("message").asText("");
        boolean explicit = request.hasNonNull("businessDate");
        if (explicit) {
            try { day = LocalDate.parse(textValue(request, "businessDate")); }
            catch (RuntimeException ignored) { throw invalid(); }
        } else if (question.contains("昨天")) day = today.minusDays(1);
        if (question.contains("今天") && !day.equals(today) || question.contains("昨天") && !day.equals(today.minusDays(1)))
            throw new AgentProblem(422, "AGENT_DATE_CONFLICT", "问题中的今天或昨天与所选业务日不一致。");
        int horizon = 1;
        if (request.has("horizonDays")) {
            JsonNode value = request.get("horizonDays");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
            horizon = value.intValue();
        }
        if (horizon != 1 && horizon != 7) throw invalid();
        ObjectNode context = mapper.createObjectNode().put("appId", app).put("businessDate", day.toString()).put("horizonDays", horizon);
        for (String key : new String[]{"runId", "fileKey", "backtestId"}) {
            if (request.hasNonNull(key)) {
                String value = textValue(request, key);
                if (value == null || value.isBlank()) throw invalid();
                context.put(key, value);
            }
        }
        return context;
    }

    private String textValue(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText().trim() : null;
    }

    private AgentProblem invalid() { return new AgentProblem(422, "AGENT_INVALID_REQUEST", "Agent 请求上下文不合法。"); }

    private JsonNode parseArguments(JsonNode call) {
        try { return mapper.readTree(call.path("function").path("arguments").asText("")); }
        catch (Exception ignored) { return null; }
    }

    private boolean validArguments(String name, JsonNode args, ObjectNode context) {
        if (!TOOLS.contains(name) || args == null || !args.isObject()) return false;
        Iterator<String> fields = args.fieldNames();
        while (fields.hasNext()) {
            String key = fields.next();
            if (!ARGUMENTS.contains(key)) return false;
            JsonNode given = args.get(key);
            JsonNode locked = context.get(key);
            if (locked == null || !given.equals(locked)) return false;
        }
        return true;
    }

    private boolean validEvidence(String name, ObjectNode item, ObjectNode context) {
        if (item == null || !name.equals(item.path("tool").asText()) || !context.path("appId").equals(item.path("appId"))) return false;
        JsonNode reported = item.path("context");
        for (String field : new String[]{"appId", "businessDate", "horizonDays", "runId"}) {
            if (context.has(field) && !context.get(field).equals(reported.get(field))) return false;
        }
        for (String field : new String[]{"fileKey", "backtestId"}) {
            if (context.has(field) && reported.has(field) && !context.get(field).equals(reported.get(field))) return false;
        }
        JsonNode data = item.path("data");
        return !data.isMissingNode() && !data.isNull() && !(data.isContainerNode() && data.isEmpty());
    }

    private <T> T bounded(Callable<T> operation, long deadline, boolean modelCall) {
        Duration time = remaining(deadline);
        Future<T> future = DEADLINE_WORKERS.submit(operation);
        try { return future.get(time.toNanos(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException ignored) {
            future.cancel(true);
            throw new AgentProblem(504, "AGENT_TIMEOUT", "Agent 请求超过时间上限。");
        } catch (InterruptedException ignored) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw new AgentProblem(504, "AGENT_TIMEOUT", "Agent 请求中断。");
        } catch (ExecutionException ignored) {
            throw new AgentProblem(503, modelCall ? "AGENT_MODEL_UNAVAILABLE" : "AGENT_TOOL_UNAVAILABLE",
                    modelCall ? "模型服务暂时不可用。" : "业务工具暂时不可用。");
        }
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new AgentProblem(504, "AGENT_TIMEOUT", "Agent 请求超过时间上限。");
        return Duration.ofNanos(nanos);
    }

    private ArrayNode toolDefinitions() {
        ArrayNode tools = mapper.createArrayNode();
        for (String name : TOOLS) {
            ObjectNode function = mapper.createObjectNode().put("name", name)
                    .put("description", "只读查询业务证据；所有参数必须与服务端锁定上下文一致。");
            ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
            ObjectNode properties = mapper.createObjectNode();
            for (String key : ARGUMENTS) properties.set(key, mapper.createObjectNode().put("type", key.equals("horizonDays") ? "integer" : "string"));
            schema.set("properties", properties);
            function.set("parameters", schema);
            tools.add(mapper.createObjectNode().put("type", "function").set("function", function));
        }
        return tools;
    }

    private ObjectNode response(String answer, ObjectNode context, ArrayNode evidence, ArrayNode uncertainties, String grounding) {
        ObjectNode result = mapper.createObjectNode().put("answer", answer).put("groundingStatus", grounding);
        result.set("context", context.deepCopy());
        result.set("evidence", evidence.deepCopy());
        result.set("uncertainties", uncertainties.deepCopy());
        result.putNull("suggestionId");
        for (JsonNode item : evidence) {
            JsonNode id = item.path("data").path("suggestionId");
            if (id.isTextual()) result.put("suggestionId", id.asText());
        }
        return result;
    }

    private String factSummary(ArrayNode evidence) {
        StringBuilder summary = new StringBuilder("已取得业务工具事实：");
        for (JsonNode item : evidence) summary.append(' ').append(item.path("tool").asText()).append(' ')
                .append(item.path("data").toString()).append('；');
        return summary.toString();
    }

    private boolean unsupportedNumbers(String answer, ArrayNode evidence) {
        String cleaned = answer.replaceAll("\\d{4}-\\d{2}-\\d{2}", "");
        Set<BigDecimal> facts = new HashSet<>();
        for (JsonNode item : evidence) collectNumbers(item.path("data"), facts);
        Matcher matcher = NUMBER.matcher(cleaned);
        while (matcher.find()) {
            String literal = matcher.group().replaceAll("[^0-9.]", "");
            if (literal.isEmpty()) continue;
            BigDecimal value;
            try { value = new BigDecimal(literal).stripTrailingZeros(); } catch (NumberFormatException ignored) { continue; }
            if (facts.contains(value)) continue;
            String suffix = matcher.group().replaceAll("[0-9.\\s]", "");
            if (suffix.equals("%") && facts.contains(value.movePointLeft(2))) continue;
            BigDecimal divisor = switch (suffix.toUpperCase()) {
                case "KB" -> BigDecimal.valueOf(1024);
                case "MB" -> BigDecimal.valueOf(1024L * 1024);
                case "GB" -> BigDecimal.valueOf(1024L * 1024 * 1024);
                default -> BigDecimal.ONE;
            };
            if (facts.contains(value.multiply(divisor).stripTrailingZeros())) continue;
            return true;
        }
        return false;
    }

    private void collectNumbers(JsonNode node, Set<BigDecimal> result) {
        if (node.isNumber()) result.add(node.decimalValue().stripTrailingZeros());
        else if (node.isTextual() && node.asText().matches("-?\\d+(?:\\.\\d+)?"))
            result.add(new BigDecimal(node.asText()).stripTrailingZeros());
        else if (node.isContainerNode()) node.forEach(child -> collectNumbers(child, result));
    }
}
