package com.yukino.cdn.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HistoryServiceTest {
    final RestClient.Builder builder = RestClient.builder().baseUrl("http://python");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final ObjectMapper mapper = new ObjectMapper();
    final Map<String,String> params = Map.of("datasetId","ds_one","appId","A","startDate","2026-01-01","endDate","2026-01-07","granularity","WEEKLY");

    @Test void forwardsValidatedFiltersAndPreservesExactObservedBytes() {
        server.expect(requestTo("http://python/api/v1/datasets/ds_one/observations?appId=A&startDate=2026-01-01&endDate=2026-01-07&granularity=WEEKLY&topK=50"))
                .andRespond(withSuccess("""
                        {"datasetId":"ds_one","appId":"A","datasetSource":"FAKE","startDate":"2026-01-01","endDate":"2026-01-07","granularity":"WEEKLY","scope":"OBSERVED_FILES","completeness":"UNKNOWN","observedTrafficBytes":"10000000000000001","days":[],"items":[],"missingDates":[],"hotspotTrafficRatio":{"value":null,"reasonCode":"NO_CONFIRMED_APP_TOTAL"}}
                        """, MediaType.APPLICATION_JSON));
        var result = new HistoryService(builder.build(), mapper).query(params);
        assertEquals("10000000000000001", result.path("observedTrafficBytes").textValue());
        assertTrue(result.path("hotspotTrafficRatio").path("value").isNull());
        server.verify();
    }

    @Test void dependencyFailureIs503RatherThanEmptySuccessfulData() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://python/"))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        var error = assertThrows(PredictionProblem.class, () -> new HistoryService(builder.build(),mapper).query(params));
        assertEquals(503,error.httpStatus());
    }

    @Test void responseCannotCrossAppOrDataset() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://python/"))).andRespond(withSuccess("{\"datasetId\":\"ds_one\",\"appId\":\"OTHER\"}", MediaType.APPLICATION_JSON));
        var error = assertThrows(PredictionProblem.class, () -> new HistoryService(builder.build(),mapper).query(params));
        assertEquals("INVALID_RESULT", error.code());
    }

    @Test void invalidWeeklyRangeAndAppAreRejectedBeforeNetwork() {
        var service = new HistoryService(builder.build(),mapper);
        var invalid = new java.util.HashMap<>(params);
        invalid.put("endDate","2026-01-03");
        assertEquals(422,assertThrows(PredictionProblem.class,()->service.query(invalid)).httpStatus());
        invalid.put("appId","");
        assertEquals(422,assertThrows(PredictionProblem.class,()->service.query(invalid)).httpStatus());
        server.verify();
    }
}
