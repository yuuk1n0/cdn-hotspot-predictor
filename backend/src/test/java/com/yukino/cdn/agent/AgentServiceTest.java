package com.yukino.cdn.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukino.cdn.operations.OperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** HTTP responses here are protocol fixtures, never evidence of a real LLM invocation. */
class AgentServiceTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-20T17:00:00Z"), ZoneOffset.UTC);
    final AgentSettings settings = new AgentSettings("http://model.test/v1", "fixture-model", "fixture-secret");
    FakeOperations operations;
    MockRestServiceServer server;
    AgentService service;

    @BeforeEach void setup() {
        operations = new FakeOperations();
        var builder = RestClient.builder().baseUrl(settings.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        service = new AgentService(operations, new AgentHttpClient(builder.build(), settings, mapper), mapper, clock, settings, Duration.ofSeconds(2));
    }

    @Test void standardMultiTurnUsesServerEvidenceAndToolCallIds() throws Exception {
        expect(call("getTopHotspots", "{}"));
        server.expect(requestTo("http://model.test/v1/chat/completions"))
            .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call_1"))
            .andExpect(jsonPath("$.messages[3].tool_call_id").value("call_1"))
            .andExpect(jsonPath("$.messages[3].role").value("tool"))
            .andExpect(jsonPath("$.model").value("fixture-model"))
            .andExpect(header("Authorization", "Bearer fixture-secret"))
            .andRespond(withSuccess(answer("流量为100B，来源MOCK，仅用于演示。"), MediaType.APPLICATION_JSON));
        var response = service.chat(request("查询热点"));
        assertEquals("GROUNDED", response.path("groundingStatus").asText());
        assertEquals("2026-10-21", response.path("context").path("businessDate").asText());
        assertEquals("100", response.path("evidence").get(0).path("data").path("predictedBytes").asText());
        assertEquals(1, operations.calls);
        server.verify();
    }

    @Test void modelCannotAnswerWithoutCallingATool() throws Exception {
        expect(answer("今天有999个热点"));
        expect(answer("依然有999个热点"));
        var response = service.chat(request("热点"));
        assertEquals("INSUFFICIENT_EVIDENCE", response.path("groundingStatus").asText());
        assertFalse(response.path("answer").asText().contains("999"));
        assertEquals(0, operations.calls);
        server.verify();
    }

    @Test void fabricatedNumbersAreReplacedByActualToolSummary() throws Exception {
        expect(call("getCompressionEffects", "{}")); expect(answer("节省999.99元，下降80%"));
        var response = service.chat(request("压缩收益"));
        assertEquals("FACT_SUMMARY", response.path("groundingStatus").asText());
        assertFalse(response.path("answer").asText().contains("999.99"));
        assertTrue(response.path("answer").asText().contains("100"));
        assertFalse(response.path("uncertainties").isEmpty());
    }

    @Test void rejectCrossAppUnknownToolAndAdditionalPropertiesBeforeBusinessCalls() throws Exception {
        for (String[] scenario : List.of(new String[]{"getTopHotspots", "{\"appId\":\"other\"}"},
                new String[]{"sendMessage", "{}"}, new String[]{"getTopHotspots", "{\"sql\":\"DELETE\"}"},
                new String[]{"getTopHotspots", "{\"horizonDays\":7}"}, new String[]{"getTopHotspots", "{\"runId\":\"other\"}"})) {
            expect(call(scenario[0], scenario[1]));
            var result = service.chat(request("查询"));
            assertEquals("INSUFFICIENT_EVIDENCE", result.path("groundingStatus").asText());
            assertFalse(result.path("uncertainties").isEmpty());
            verifyAndReset();
        }
        assertEquals(0, operations.calls);
    }

    @Test void malformedArgumentsAndStringHorizonAreRejected() throws Exception {
        expect(call("getTopHotspots", "{"));
        assertEquals("INSUFFICIENT_EVIDENCE", service.chat(request("查询")).path("groundingStatus").asText());
        verifyAndReset();
        expect(call("getTopHotspots", "{\"horizonDays\":\"1\"}"));
        assertEquals("INSUFFICIENT_EVIDENCE", service.chat(request("查询")).path("groundingStatus").asText());
        assertEquals(0, operations.calls);
    }

    @Test void atMostThreeToolsAndFourModelRequests() throws Exception {
        String repeated = call("getTopHotspots", "{}");
        for (int i=0;i<4;i++) expect(repeated);
        var response = service.chat(request("热点"));
        assertEquals(4, operations.calls);
        assertFalse(response.path("uncertainties").isEmpty());
        verifyAndReset();
        String tool = "{\"id\":\"x\",\"type\":\"function\",\"function\":{\"name\":\"getTopHotspots\",\"arguments\":\"{}\"}}";
        expect("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + String.join(",", Collections.nCopies(4, tool)) + "]}}]}");
        int before = operations.calls;
        assertEquals("INSUFFICIENT_EVIDENCE", service.chat(request("热点")).path("groundingStatus").asText());
        assertEquals(before, operations.calls);
        server.verify();
    }

    @Test void actualShanghaiTodayAndYesterdayRejectHistoricalContext() throws Exception {
        ObjectNode req=request("今天的热点"); req.put("businessDate", "2026-10-20").put("runId", "old");
        assertEquals("AGENT_DATE_CONFLICT", assertThrows(AgentProblem.class, () -> service.chat(req)).code());
        ObjectNode yesterday=request("昨天模型准确率"); yesterday.put("businessDate", "2026-10-19");
        assertEquals("AGENT_DATE_CONFLICT", assertThrows(AgentProblem.class, () -> service.chat(yesterday)).code());
        // No caller date means yesterday must still use the actual clock, not default today.
        expect(call("getModelMetrics", "{}")); expect(answer("指标未知"));
        var response=service.chat(request("昨天模型准确率"));
        assertEquals("2026-10-20", response.path("context").path("businessDate").asText());
    }

    @Test void selectedRunCannotSilentlyOverrideToday() throws Exception {
        operations.resultDate="2026-10-01";
        expect(call("getTopHotspots", "{}"));
        var req=request("今天的热点"); req.put("runId", "old");
        var response=service.chat(req);
        assertEquals("INSUFFICIENT_EVIDENCE", response.path("groundingStatus").asText());
        assertTrue(response.path("evidence").isEmpty());
    }

    @Test void noConfigurationReturns503AndStatusNeverContainsCredentials() throws Exception {
        var absent=new AgentSettings("", "", "");
        var disabled=new AgentService(operations, (body, remaining)-> { fail("must not call model"); return null; }, mapper, clock, absent, Duration.ofSeconds(1));
        var error=assertThrows(AgentProblem.class, ()->disabled.chat(request("热点")));
        assertEquals(503,error.httpStatus()); assertEquals("AGENT_NOT_CONFIGURED",error.code());
        assertFalse(service.status().toString().contains("fixture-secret"));
        assertFalse(service.status().toString().contains("model.test"));
        assertEquals(4,service.status().path("maxModelRequests").asInt());
    }

    @Test void failuresAreSanitizedAndNeverProduceEvidence() throws Exception {
        server.expect(requestTo("http://model.test/v1/chat/completions"))
            .andRespond(withServerError().body("fixture-secret http://signed.secret/?key=fixture-secret"));
        var error=assertThrows(AgentProblem.class,()->service.chat(request("热点")));
        assertEquals(503,error.httpStatus()); assertFalse(error.getMessage().contains("fixture-secret"));
        verifyAndReset();
        operations.failure = new IllegalStateException("fixture-secret");
        expect(call("getTopHotspots","{}"));
        var response=service.chat(request("热点"));
        assertTrue(response.path("evidence").isEmpty());
        assertFalse(response.toString().contains("fixture-secret"));
    }

    @Test void wholeDeadlineIncludesModelAndTools() throws Exception {
        var slowModel=new AgentService(operations,(body,remaining)->{ try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } return mapper.createObjectNode(); },mapper,clock,settings,Duration.ofMillis(40));
        long start=System.nanoTime();
        assertEquals(504,assertThrows(AgentProblem.class,()->slowModel.chat(request("热点"))).httpStatus());
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1000);
        operations.delayMillis=2000;
        // An in-process protocol function isolates the tool timeout rather than opening a network connection.
        var slowTool=new AgentService(operations,(body,remaining)->mapper.readTree(call("getTopHotspots","{}")),mapper,clock,settings,Duration.ofMillis(40));
        AgentService finalSlowTool=slowTool;
        assertEquals(504,assertThrows(AgentProblem.class,()->finalSlowTool.chat(request("热点"))).httpStatus());
    }

    @Test void absentDataIsNotAZeroOrSuccess() throws Exception {
        operations.missing=true;
        expect(call("getTopHotspots","{}")); expect(answer("数据正常"));
        var response=service.chat(request("热点"));
        assertEquals("INSUFFICIENT_EVIDENCE",response.path("groundingStatus").asText());
        assertFalse(response.path("uncertainties").isEmpty());
    }

    ObjectNode request(String message) {return mapper.createObjectNode().put("appId","10350").put("message",message);}
    String answer(String answer) throws Exception {return mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content",answer)))));}
    String call(String name,String arguments) throws Exception {return mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","tool_calls",List.of(Map.of("id","call_1","type","function","function",Map.of("name",name,"arguments",arguments))))))));}
    void expect(String response) {server.expect(requestTo("http://model.test/v1/chat/completions")).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));}
    void verifyAndReset() { server.verify(); server.reset(); }

    class FakeOperations implements OperationsService {
        int calls; String resultDate; RuntimeException failure; boolean missing; long delayMillis;
        public ObjectNode evidence(String tool,JsonNode context,JsonNode arguments) {
            calls++; if(failure!=null) throw failure;
            try {Thread.sleep(delayMillis);} catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            ObjectNode effective=context.deepCopy(); if(resultDate!=null)effective.put("businessDate",resultDate);
            ObjectNode node=mapper.createObjectNode().put("tool",tool).put("appId",context.path("appId").asText());
            node.set("context",effective); node.put("queriedAt",clock.instant().toString());
            if(missing){node.putNull("data");node.putArray("reasonCodes").add("NO_CURRENT_RUN");}
            else {node.putObject("data").put("predictedBytes","100").put("predictionSource","MOCK"); node.putArray("reasonCodes");}
            return node;
        }
        public ObjectNode config(String appId){throw new UnsupportedOperationException();}
        public ObjectNode updateConfig(String appId,JsonNode body){throw new UnsupportedOperationException();}
        public List<String> configuredApps(){return List.of();}
        public ObjectNode run(String appId,String runId,LocalDate date,int horizon){throw new UnsupportedOperationException();}
        public ObjectNode pending(String appId,String runId,String source){throw new UnsupportedOperationException();}
        public ObjectNode overdue(String appId,String runId,String source){throw new UnsupportedOperationException();}
        public ObjectNode suggestion(String appId,int horizon,String backtestId){throw new UnsupportedOperationException();}
    }
}
