package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.operations.OperationsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeishuCardActionControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AlertIncidentRepository repository;
    private AlertIncidentService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var clock = Clock.fixed(Instant.parse("2026-10-22T06:05:00Z"), ZoneOffset.UTC);
        var jdbc = new NamedParameterJdbcTemplate(data);
        var routes = new AlertRouteService(new OperationsRepository(jdbc, mapper), mapper, clock);
        configure(routes);
        repository = new AlertIncidentRepository(jdbc, mapper);
        var sender = new FakeAlertSender();
        var dispatcher = new AlertDispatcher(repository, new AlertDeliveryRepository(jdbc, mapper),
                routes, sender, new AlertCardRenderer(mapper), clock);
        service = new AlertIncidentService(repository, routes, mapper, clock, dispatcher);
        var settings = new AlertSettings("", "", "callback-token", "ingest-token",
                "https://open.feishu.cn", false);
        mvc = MockMvcBuilders.standaloneSetup(new FeishuCardActionController(
                        service, new FeishuCallbackVerifier(settings, mapper)))
                .setControllerAdvice(new FeishuCardActionApiAdvice()).build();
    }

    @Test
    void verifiedOwnerCanAcknowledgeCardUsingTrustedOperatorId() throws Exception {
        var receipt = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:00:00Z"));
        var incident = repository.get(receipt.path("incidentId").asText());

        mvc.perform(post("/api/v1/feishu/card-actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action("callback-token", "ou_owner", "ACK",
                                incident.path("incidentId").asText(), incident.path("version").asInt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        var stored = repository.get(incident.path("incidentId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("ACKED", stored.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("ou_owner", stored.path("acknowledgedBy").asText());
        org.junit.jupiter.api.Assertions.assertFalse(stored.hasNonNull("nextReminderAt"));
    }

    @Test
    void invalidTokenAndNonOwnerCannotChangeIncident() throws Exception {
        var receipt = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1", "2026-10-22T06:00:00Z"));
        var incident = repository.get(receipt.path("incidentId").asText());

        mvc.perform(post("/api/v1/feishu/card-actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action("wrong", "ou_owner", "ACK",
                                incident.path("incidentId").asText(), incident.path("version").asInt())))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/feishu/card-actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action("callback-token", "ou_outsider", "ACK",
                                incident.path("incidentId").asText(), incident.path("version").asInt())))
                .andExpect(status().isForbidden());

        org.junit.jupiter.api.Assertions.assertEquals("OPEN",
                repository.get(incident.path("incidentId").asText()).path("status").asText());
    }

    @Test
    void responsibleOwnerCanMoveIncidentToProcessingAndRecovered() throws Exception {
        var receipt = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:3", "2026-10-22T06:00:00Z"));
        String incidentId = receipt.path("incidentId").asText();

        var ackIncident = repository.get(incidentId);
        mvc.perform(post("/api/v1/feishu/card-actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action("callback-token", "ou_owner", "ACK",
                                incidentId, ackIncident.path("version").asInt())))
                .andExpect(status().isOk());

        var processingIncident = repository.get(incidentId);
        mvc.perform(post("/api/v1/feishu/card-actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action("callback-token", "ou_owner", "PROCESSING",
                                incidentId, processingIncident.path("version").asInt())))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals("PROCESSING",
                repository.get(incidentId).path("status").asText());

        mvc.perform(post("/api/v1/feishu/card-actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action("callback-token", "ou_owner", "RECOVER",
                                incidentId, repository.get(incidentId).path("version").asInt())))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals("RECOVERED",
                repository.get(incidentId).path("status").asText());
    }

    @Test
    void configuredEncryptKeyRequiresAValidLarkSignature() throws Exception {
        var receipt = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:4", "2026-10-22T06:00:00Z"));
        var incident = repository.get(receipt.path("incidentId").asText());
        String raw = action("callback-token", "ou_owner", "ACK",
                incident.path("incidentId").asText(), incident.path("version").asInt());
        String timestamp = "1780000000";
        String nonce = "nonce-1";
        String key = "encrypt-key";
        String signature = signature(timestamp, nonce, key, raw);
        var signedMvc = signedMvc(key);

        signedMvc.perform(post("/api/v1/feishu/card-actions")
                        .header("X-Lark-Request-Timestamp", timestamp)
                        .header("X-Lark-Request-Nonce", nonce)
                        .header("X-Lark-Signature", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isUnauthorized());

        signedMvc.perform(post("/api/v1/feishu/card-actions")
                        .header("X-Lark-Request-Timestamp", timestamp)
                        .header("X-Lark-Request-Nonce", nonce)
                        .header("X-Lark-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isOk());
    }

    @Test
    void encryptedSignedCallbackIsDecryptedBeforeApplyingAction() throws Exception {
        var receipt = service.ingest(AlertTestFixtures.alert(
                "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:5", "2026-10-22T06:00:00Z"));
        var incident = repository.get(receipt.path("incidentId").asText());
        String plain = action("callback-token", "ou_owner", "ACK",
                incident.path("incidentId").asText(), incident.path("version").asInt());
        String key = "encrypt-key";
        String encrypted = encrypt(plain, key);
        String raw = mapper.createObjectNode().put("encrypt", encrypted).toString();
        String timestamp = "1780000000";
        String nonce = "nonce-2";

        signedMvc(key).perform(post("/api/v1/feishu/card-actions")
                        .header("X-Lark-Request-Timestamp", timestamp)
                        .header("X-Lark-Request-Nonce", nonce)
                        .header("X-Lark-Signature", signature(timestamp, nonce, key, raw))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals("ACKED",
                repository.get(incident.path("incidentId").asText()).path("status").asText());
    }

    private String action(String token, String openId, String action, String incidentId, int version) {
        var value = mapper.createObjectNode().put("action", action)
                .put("incidentId", incidentId).put("version", version);
        return mapper.createObjectNode()
                .put("type", "card.action.trigger")
                .put("token", token)
                .put("operator_id", openId)
                .put("action_tag", "button")
                .put("action_value", value.toString())
                .toString();
    }

    private void configure(AlertRouteService routes) {
        var body = mapper.createObjectNode().put("expectedVersion", 0)
                .put("alertsEnabled", true).put("businessName", "内容分发")
                .put("fixedChatId", "oc_fixed").put("testChatId", "oc_test")
                .put("p1AutoCreateGroup", true)
                .put("ackDeadlineMinutes", 5).put("remindIntervalMinutes", 15)
                .put("maxReminderCount", 4).put("activeWindowMinutes", 60);
        body.putArray("ownerOpenIds").add("ou_owner");
        body.putArray("backupOpenIds").add("ou_backup");
        routes.update("10350", body);
    }

    private MockMvc signedMvc(String encryptKey) {
        var settings = new AlertSettings("", "", "callback-token", "ingest-token",
                encryptKey, "https://open.feishu.cn", false);
        return MockMvcBuilders.standaloneSetup(new FeishuCardActionController(
                        service, new FeishuCallbackVerifier(settings, mapper)))
                .setControllerAdvice(new FeishuCardActionApiAdvice()).build();
    }

    private static String signature(String timestamp, String nonce, String key, String body) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest((timestamp + nonce + key + body).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }

    private static String encrypt(String body, String key) throws Exception {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(body.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }
}
