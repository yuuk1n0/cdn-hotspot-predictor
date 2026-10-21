package com.yukino.cdn.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CStoreTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RestClient.Builder builder = RestClient.builder().baseUrl("http://cstore-test");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final MockEnvironment env = new MockEnvironment().withProperty("CSTORE_BASE_URL", "http://cstore-test")
            .withProperty("CSTORE_AUTH_NONE_CONFIRMED", "true").withProperty("CSTORE_SIZE_UNIT_BYTES_CONFIRMED", "true")
            .withProperty("CSTORE_MODE", "TEST").withProperty("CSTORE_APP_MAPPINGS_JSON", """
            {"A":{"accessAppId":"download-A","metadataAppId":123,"keyMode":"EXACT"}}
            """);
    CStoreClient client() { return new CStoreClient(new CStoreSettings(env, mapper), builder.build(), new CStoreLimiter(20, 2, System::nanoTime)); }

    @Test void numericAppUsesConfirmedUrlPathMappingWithoutChangingEncodedKey() {
        var settings = new CStoreSettings(env, mapper);
        var mapping = settings.mapping("10066");
        assertEquals("10066", mapping.accessAppId());
        assertEquals(new java.math.BigInteger("10066"), mapping.metadataAppId());
        assertEquals("pro-ali-pri/encloud-162193381711225aa6202", mapping.fileKey("https://easynote-store-bs.seewo.com/pro-ali-pri/encloud-162193381711225aa6202"));
        assertEquals("dir/a%2Fb%20c", mapping.fileKey("https://store.test/dir/a%2Fb%20c?token=discard#fragment"));
        assertEquals("already/a", mapping.fileKey("already/a"));
        assertThrows(PredictionProblem.class, () -> mapping.fileKey("https://store.test/"));
        assertThrows(PredictionProblem.class, () -> mapping.fileKey("ftp://store.test/a"));
        assertThrows(PredictionProblem.class, () -> mapping.fileKey("https://user:pass@store.test/a"));
    }

    @Test void actualMetadataUsesCamelCaseFSize() {
        server.expect(requestTo("http://cstore-test/cstore/api/v2/file/meta"))
                .andRespond(withSuccess("{\"code\":0,\"data\":[{\"fileKey\":\"f\",\"fSize\":9007199254740993,\"error\":false,\"mimeType\":\"video/mp4\"}]}", MediaType.APPLICATION_JSON));
        assertEquals("9007199254740993", service("REAL", List.of("f")).metadata("run_one", List.of("f")).items().getFirst().fileSizeBytes());
        server.verify();
    }
    CStoreFileService service(String source, List<String> keys) {
        return new CStoreFileService(id -> {
            var root = mapper.createObjectNode().put("runId", id).put("appId", "A").put("datasetSource", source);
            var items = root.putArray("items"); keys.forEach(key -> items.addObject().put("fileKey", key));
            return root;
        }, new CStoreSettings(env, mapper), client());
    }

    @Test void metadataPostsNumericAppAndRetainsIntegerPrecisionAndPartialErrors() throws Exception {
        server.expect(requestTo("http://cstore-test/cstore/api/v2/file/meta")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"appId\":123,\"fileKeys\":[\"a&b?#c\",\"missing\"]}"))
                .andRespond(withSuccess("""
                {"code":0,"data":[{"fileKey":"a&b?#c","fsize":10000000000000001,"error":false,"mimeType":"image/png"},{"fileKey":"missing","error":true,"message":"secret upstream text"}],"message":"Ok"}
                """, MediaType.APPLICATION_JSON));
        var result = service("REAL", List.of("a&b?#c", "missing")).metadata("run_one", List.of("a&b?#c", "missing"));
        assertEquals("10000000000000001", result.items().get(0).fileSizeBytes());
        assertEquals("CSTORE_TEST", result.items().get(0).dataSource());
        assertNull(result.items().get(1).fileSizeBytes());
        assertEquals("FILE_METADATA_UNAVAILABLE", result.items().get(1).errorCode());
        assertFalse(mapper.writeValueAsString(result).contains("secret"));
        server.verify();
    }

    @Test void fiftyMetadataFilesAreSplitIntoFiveRequestsOfTen() {
        List<String> keys = java.util.stream.IntStream.range(0, 50).mapToObj(i -> "f" + i).toList();
        for (int offset = 0; offset < 50; offset += 10) {
            var response = mapper.createObjectNode().put("code", 0); var data = response.putArray("data");
            keys.subList(offset, offset + 10).forEach(k -> data.addObject().put("fileKey", k).put("fsize", 0).put("error", false));
            server.expect(requestTo("http://cstore-test/cstore/api/v2/file/meta")).andExpect(jsonPath("$.fileKeys.length()").value(10))
                    .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        }
        var result = service("REAL", keys).metadata("run_one", keys);
        assertEquals(50, result.items().size());
        assertTrue(result.items().stream().allMatch(item -> "0".equals(item.fileSizeBytes())));
        server.verify();
    }

    @Test void accessPostsStringAppAndGetsANewUrlOnEveryCallWithoutInventingExpiry() {
        for (String token : List.of("one", "two")) {
            server.expect(requestTo("http://cstore-test/cstore/api/v3/downloadUrl")).andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("{\"appId\":\"download-A\",\"fileKeys\":[\"image\"]}", true))
                    .andRespond(withSuccess("{\"code\":0,\"data\":[{\"fileKey\":\"image\",\"downloadUrl\":\"https://media.test/image?token=" + token + "\"}]}", MediaType.APPLICATION_JSON));
        }
        var service = service("REAL", List.of("image"));
        var first = service.access("run_one", "image", "PREVIEW");
        var second = service.access("run_one", "image", "DOWNLOAD");
        assertNotEquals(first.url(), second.url()); assertNull(first.expiresAt());
        assertEquals("A", second.appId()); assertEquals("DOWNLOAD", second.purpose());
        server.verify();
    }

    @Test void fakeSnapshotsAndUnlistedFilesNeverCallCStore() {
        assertEquals("FILE_SOURCE_NOT_REAL", assertThrows(PredictionProblem.class, () -> service("FAKE", List.of("f")).access("run_one", "f", "PREVIEW")).code());
        assertEquals("FILE_CONTEXT_MISMATCH", assertThrows(PredictionProblem.class, () -> service("REAL", List.of("f")).access("run_one", "other", "PREVIEW")).code());
        assertEquals(422, assertThrows(PredictionProblem.class, () -> service("REAL", List.of("f")).metadata("run_one", Collections.nCopies(51, "f"))).httpStatus());
        server.verify();
    }

    @Test void missingConfigurationAndUnconfirmedIdentityOrUnitsDoNotGuess() {
        var absent = new CStoreSettings(new MockEnvironment(), mapper);
        assertEquals("CSTORE_NOT_CONFIGURED", assertThrows(PredictionProblem.class, absent::requireConfigured).code());
        var settings = new CStoreSettings(env, mapper);
        assertEquals("IDENTITY_UNRESOLVED", assertThrows(PredictionProblem.class, () -> settings.mapping("OTHER")).code());
        env.withProperty("CSTORE_SIZE_UNIT_BYTES_CONFIRMED", "false");
        assertEquals("CSTORE_UNIT_UNCONFIRMED", assertThrows(PredictionProblem.class, () -> service("REAL", List.of("f")).metadata("run_one", List.of("f"))).code());
        server.verify();
    }

    @Test void explicitKeyMappingMatchesReturnedStorageKeyBackToOriginalBusinessKey() {
        env.withProperty("CSTORE_APP_MAPPINGS_JSON", "{\"A\":{\"accessAppId\":\"download-A\",\"metadataAppId\":123,\"keyMode\":\"MAPPED\",\"fileKeys\":{\"https://old.test/a?b\":\"real/a\"}}}");
        server.expect(requestTo("http://cstore-test/cstore/api/v2/file/meta")).andExpect(content().json("{\"appId\":123,\"fileKeys\":[\"real/a\"]}"))
                .andRespond(withSuccess("{\"code\":0,\"data\":[{\"fileKey\":\"real/a\",\"fsize\":7,\"error\":false}]}", MediaType.APPLICATION_JSON));
        assertEquals("https://old.test/a?b", service("REAL", List.of("https://old.test/a?b")).metadata("run_one", List.of("https://old.test/a?b")).items().getFirst().fileKey());
        server.verify();
    }

    @Test void invalidOrEmptyUrlsAndCrossFileResponsesAreRejected() {
        for (String url : List.of("", "javascript:alert(1)", "https://user:secret@host.test/file")) {
            var b = RestClient.builder().baseUrl("http://test"); var mock = MockRestServiceServer.bindTo(b).build();
            var root = mapper.createObjectNode().put("code", 0); root.putArray("data").addObject().put("fileKey", "f").put("downloadUrl", url);
            mock.expect(requestTo("http://test/cstore/api/v3/downloadUrl")).andRespond(withSuccess(root.toString(), MediaType.APPLICATION_JSON));
            var c = new CStoreClient(new CStoreSettings(env, mapper), b.build(), new CStoreLimiter(20, 2, System::nanoTime));
            assertThrows(PredictionProblem.class, () -> c.access(new CStoreSettings(env, mapper).mapping("A"), "f")); mock.verify();
        }
    }

    @Test void upstreamAuthErrorIsSanitized() {
        server.expect(requestTo("http://cstore-test/cstore/api/v3/downloadUrl")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret-token-and-signed-url"));
        var error = assertThrows(PredictionProblem.class, () -> service("REAL", List.of("f")).access("run_one", "f", "PREVIEW"));
        assertEquals("CSTORE_AUTH_REJECTED", error.code());
        assertFalse(error.getMessage().contains("secret")); assertNull(error.getCause());
        server.verify();
    }

    @Test void globalSlidingWindowAndConcurrentCapacityAreBoundedWithoutQueue() {
        AtomicLong now = new AtomicLong(); var limiter = new CStoreLimiter(20, 2, now::get);
        for (int i = 0; i < 20; i++) try (var ignored = limiter.acquire()) { }
        assertEquals(429, assertThrows(PredictionProblem.class, limiter::acquire).httpStatus());
        now.set(999_999_999); assertThrows(PredictionProblem.class, limiter::acquire);
        now.set(1_000_000_000); var first = limiter.acquire(); var second = limiter.acquire();
        assertThrows(PredictionProblem.class, limiter::acquire);
        first.close(); second.close();
        try (var ignored = limiter.acquire()) { }
    }
}
