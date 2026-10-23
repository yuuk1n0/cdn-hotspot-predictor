package com.yukino.cdn.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.prediction.PredictionProblem;
import com.yukino.cdn.prediction.PredictionSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class HistoryService {
    private final RestClient http;
    private final ObjectMapper mapper;

    @Autowired
    public HistoryService(PredictionSettings settings, ObjectMapper mapper) {
        this(client(settings), mapper);
    }

    HistoryService(RestClient http, ObjectMapper mapper) { this.http = http; this.mapper = mapper; }

    private static RestClient client(PredictionSettings settings) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int)settings.connectTimeout().toMillis());
        factory.setReadTimeout((int)settings.readTimeout().toMillis());
        return RestClient.builder().baseUrl(settings.pythonBaseUrl()).requestFactory(factory).build();
    }

    public JsonNode query(Map<String,String> params) {
        String dataset = required(params,"datasetId"), app = required(params,"appId");
        String start = required(params,"startDate"), end = required(params,"endDate");
        String granularity = params.getOrDefault("granularity","DAILY");
        int topK;
        try {
            var first = LocalDate.parse(start); var last = LocalDate.parse(end);
            long days = ChronoUnit.DAYS.between(first,last);
            topK = Integer.parseInt(params.getOrDefault("topK","50"));
            if (days < 0 || days > 365 || topK < 1 || topK > 50 || !start.equals(first.toString()) || !end.equals(last.toString())
                    || !(granularity.equals("DAILY") || granularity.equals("WEEKLY")) || (granularity.equals("WEEKLY") && days != 6)) throw new IllegalArgumentException();
        } catch (RuntimeException ignored) { throw new PredictionProblem(422,"INVALID_DATE_RANGE","日期范围无效：日最多366天、周必须七天，topK为1至50。"); }
        Map<String,Object> variables = new LinkedHashMap<>();
        variables.put("dataset",dataset); variables.put("app",app); variables.put("start",start); variables.put("end",end);
        variables.put("granularity",granularity); variables.put("topK",topK);
        if (params.containsKey("fileKey")) variables.put("fileKey",params.get("fileKey"));
        try {
            var result = http.get().uri(builder -> {
                builder.path("/api/v1/datasets/{dataset}/observations").queryParam("appId","{app}")
                        .queryParam("startDate","{start}").queryParam("endDate","{end}")
                        .queryParam("granularity","{granularity}").queryParam("topK","{topK}");
                if (variables.containsKey("fileKey")) builder.queryParam("fileKey","{fileKey}");
                return builder.build(variables);
            }).retrieve().body(JsonNode.class);
            if (result == null || !dataset.equals(result.path("datasetId").asText()) || !app.equals(result.path("appId").asText())
                    || !start.equals(result.path("startDate").asText()) || !end.equals(result.path("endDate").asText())
                    || !granularity.equals(result.path("granularity").asText()) || !"OBSERVED_FILES".equals(result.path("scope").asText())
                    || !("REAL".equals(result.path("datasetSource").asText()) || "FAKE".equals(result.path("datasetSource").asText()))
                    || !result.path("days").isArray() || !result.path("items").isArray() || !result.path("missingDates").isArray())
                throw new PredictionProblem(422,"INVALID_RESULT","历史观测响应与请求上下文不符。");
            return result;
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status >= 500 || status == 429) throw unavailable();
            if (status == 404) throw new PredictionProblem(404,"DATASET_NOT_FOUND","Python观测接口或数据集不存在，请核对已部署历史模块。");
            try {
                var detail = mapper.readTree(error.getResponseBodyAsString()).path("error");
                if (detail.path("code").isTextual() && detail.path("message").isTextual())
                    throw new PredictionProblem(status,detail.path("code").asText(),detail.path("message").asText());
            } catch (java.io.IOException ignored) { /* Do not expose arbitrary upstream bodies. */ }
            throw new PredictionProblem(422,"PYTHON_REQUEST_REJECTED","Python拒绝历史查询参数。");
        } catch (RestClientException error) { throw unavailable(); }
    }

    private static String required(Map<String,String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank() || value.length() > 128 || !value.equals(value.trim()) || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw new PredictionProblem(422,"INVALID_REQUEST","必须提供有效的"+key+"。");
        return value;
    }

    private static PredictionProblem unavailable() { return new PredictionProblem(503,"PYTHON_UNAVAILABLE","Python历史查询暂不可用；未返回零流量或替代数据。"); }
}
