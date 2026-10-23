# 飞书事故告警与漏处理提醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现按 AppID 路由的飞书事故告警、P1 自动建群、未认领提醒、原事故更新和恢复闭环。

**Architecture:** Java 新增独立的 `alert` 包，负责告警入站、事故去重、状态机、路由、飞书企业应用机器人和提醒调度；复用 `app_config` 保存路由，复用 `notification_delivery` 保存投递审计。Vue 通知中心改为“实时告警 / 业务通知配置 / 发送记录”三个页签。

**Tech Stack:** Java 21、Spring Boot 3.3.5、Spring JDBC、Jackson、JUnit 5、H2 MySQL mode、Vue 3、Vue Router、Node test、MySQL 8。

## Global Constraints

- 每次只执行一个 Task；完成该 Task 的测试和验证后停止，等待用户批准下一 Task。
- 先写失败测试，再做最小实现；禁止先写生产代码。
- P1 自动创建事故群；建群失败时发送固定应急群，并记录 `GROUP_CREATE_FAILED`。
- P2/P3 仅发送到该 AppID 的固定应急群。
- 同一 `appId + incidentType + objectKey + activeWindow` 只允许一个未恢复事故。
- 后续异常更新原事故和原群，不重复建群。
- 恢复后发送恢复卡片，并停止全部未处理提醒。
- `HTTP 202` 只表示事件接收成功，不代表飞书已发送。
- 告警配置默认关闭；真实发送必须获得明确授权。
- 飞书凭证只来自服务端环境变量，不进入数据库、前端、日志或接口响应。
- `006_incident_alerts.sql` 只能人工审核和执行，应用启动不得自动迁移。
- 未获得用户明确授权，不执行 `git commit`、真实飞书群操作或生产 MySQL 写入。
- 任何 Task 完成后都不得自动进入下一 Task。

---

## 文件结构

后端新增：

- `backend/src/main/java/com/yukino/cdn/alert/AlertSettings.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertRouteService.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertRouteController.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertEvent.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertIncidentRepository.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertIncidentService.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertController.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertDeliveryRepository.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertCardRenderer.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertSender.java`
- `backend/src/main/java/com/yukino/cdn/alert/FeishuOpenApiClient.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertDispatcher.java`
- `backend/src/main/java/com/yukino/cdn/alert/AlertReminderScheduler.java`
- `backend/src/main/java/com/yukino/cdn/alert/FeishuCallbackVerifier.java`
- `backend/src/main/java/com/yukino/cdn/alert/FeishuCardActionController.java`

前端新增：

- `frontend/src/alerts.js`
- `frontend/src/components/alerts/IncidentPanel.vue`
- `frontend/src/components/alerts/AlertRoutePanel.vue`
- `frontend/src/components/alerts/AlertDeliveryPanel.vue`

数据库与文档：

- `backend/src/main/resources/db/migrations/006_incident_alerts.sql`
- `docs/superpowers/acceptance/2026-10-22-feishu-incident-alert-checklist.md`

---

### Task 1: 增量数据库契约（实现完成，提交待授权）

**Files:**

- Create: `backend/src/main/resources/db/migrations/006_incident_alerts.sql`
- Create: `backend/src/test/java/com/yukino/cdn/alert/AlertMigrationContractTest.java`
- Modify: `backend/src/test/resources/operations-test-schema.sql`

**Interfaces:**

- Produces: `alert_event`，唯一键 `(app_id, source_event_id)`。
- Produces: `alert_incident`，可空唯一字段 `active_fingerprint`。
- Produces: `notification_delivery.incident_id/alert_phase/chat_id/feishu_message_id`。

- [x] **Step 1: 写迁移契约测试**

```java
package com.yukino.cdn.alert;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AlertMigrationContractTest {
    @Test void migrationDefinesIdempotentEventsAndActiveIncidentUniqueness() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migrations/006_incident_alerts.sql"));
        assertTrue(sql.contains("CREATE TABLE alert_event"));
        assertTrue(sql.contains("UNIQUE KEY uk_alert_event_source (app_id, source_event_id)"));
        assertTrue(sql.contains("CREATE TABLE alert_incident"));
        assertTrue(sql.contains("active_fingerprint VARCHAR(64)"));
        assertTrue(sql.contains("UNIQUE KEY uk_alert_active (active_fingerprint)"));
        assertTrue(sql.contains("ADD COLUMN incident_id VARCHAR(64)"));
        assertTrue(sql.contains("ADD COLUMN alert_phase VARCHAR(32)"));
        assertTrue(sql.contains("ADD COLUMN chat_id VARCHAR(128)"));
        assertTrue(sql.contains("ADD COLUMN feishu_message_id VARCHAR(128)"));
    }
}
```

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -o -Dtest=AlertMigrationContractTest test
```

Expected: FAIL，因为 `006_incident_alerts.sql` 不存在。

- [x] **Step 3: 创建迁移和 H2 测试表**

迁移必须包含：

```sql
CREATE TABLE alert_event (
    id VARCHAR(64) NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_event_id VARCHAR(128) NOT NULL,
    event_kind VARCHAR(16) NOT NULL,
    incident_id VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    received_at_ms BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_event_source (app_id, source_event_id),
    KEY idx_alert_event_incident (incident_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE alert_incident (
    id VARCHAR(64) NOT NULL,
    app_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    severity VARCHAR(8) NOT NULL,
    incident_type VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    active_fingerprint VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    routing_status VARCHAR(16) NOT NULL,
    routing_error VARCHAR(64),
    chat_mode VARCHAR(24),
    chat_id VARCHAR(128),
    opening_message_id VARCHAR(128),
    first_seen_at_ms BIGINT NOT NULL,
    last_seen_at_ms BIGINT NOT NULL,
    occurrence_count INT NOT NULL,
    ack_deadline_at_ms BIGINT NOT NULL,
    acknowledged_at_ms BIGINT,
    acknowledged_by VARCHAR(128),
    recovered_at_ms BIGINT,
    reminder_count INT NOT NULL,
    next_reminder_at_ms BIGINT,
    escalated_at_ms BIGINT,
    details_json LONGTEXT NOT NULL,
    version INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_active (active_fingerprint),
    KEY idx_alert_incident_app_status (app_id, status, first_seen_at_ms),
    KEY idx_alert_incident_reminder (status, next_reminder_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE notification_delivery
    ADD COLUMN incident_id VARCHAR(64),
    ADD COLUMN alert_phase VARCHAR(32),
    ADD COLUMN chat_id VARCHAR(128),
    ADD COLUMN feishu_message_id VARCHAR(128),
    ADD KEY idx_notification_incident (incident_id, created_at_ms);
```

`backend/src/test/resources/operations-test-schema.sql` 同步加入同名字段和表，但使用 H2 可接受的 `VARCHAR/CLOB` 类型。

- [x] **Step 4: 运行测试和差异检查**

Run:

```bash
cd backend
mvn -o -Dtest=AlertMigrationContractTest test
cd ..
git diff --check
```

Expected: PASS，且无空白字符错误。

- [ ] **Step 5: 提交（未执行，等待用户明确授权）**

```bash
git add backend/src/main/resources/db/migrations/006_incident_alerts.sql \
  backend/src/test/java/com/yukino/cdn/alert/AlertMigrationContractTest.java \
  backend/src/test/resources/operations-test-schema.sql
git commit -m "feat: add incident alert storage contract"
```

---

### Task 2: AppID 告警路由配置（实现完成，提交待授权）

**Files:**

- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertRouteService.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertRouteController.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertRouteApiAdvice.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/AlertRouteServiceTest.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/AlertRouteControllerTest.java`

**Interfaces:**

- Consumes: `OperationsRepository.config()`, `OperationsRepository.saveConfig()`。
- Produces: `ObjectNode AlertRouteService.get(String appId)`。
- Produces: `ObjectNode AlertRouteService.update(String appId, JsonNode body)`。
- Produces: `List<ObjectNode> AlertRouteService.list()`。
- Produces: `ArrayNode AlertRouteService.listJson()`。

- [x] **Step 1: 写配置失败测试**

```java
package com.yukino.cdn.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yukino.cdn.operations.OperationsRepository;
import com.yukino.cdn.prediction.PredictionProblem;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AlertRouteServiceTest {
    @Test void appRoutesAreIsolatedVersionedAndDefaultOff() {
        var mapper = new ObjectMapper();
        var data = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-test-schema.sql")).execute(data);
        var service = new AlertRouteService(
                new OperationsRepository(new NamedParameterJdbcTemplate(data), mapper), mapper,
                Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));

        var body = mapper.createObjectNode().put("expectedVersion", 0)
                .put("alertsEnabled", true).put("businessName", "内容分发")
                .put("fixedChatId", "oc_10350").put("testChatId", "oc_test_10350")
                .put("p1AutoCreateGroup", true)
                .put("ackDeadlineMinutes", 5).put("remindIntervalMinutes", 15)
                .put("maxReminderCount", 4).put("activeWindowMinutes", 60);
        body.putArray("ownerOpenIds").add("ou_owner");
        body.putArray("backupOpenIds").add("ou_backup");
        var saved = service.update("10350", body);
        assertEquals("oc_10350", saved.path("fixedChatId").asText());
        assertEquals(1, saved.path("version").asInt());
        assertEquals("oc_10350", service.get("10350").path("fixedChatId").asText());
        assertNotEquals(service.get("10350").path("fixedChatId").asText(), service.get("10066").path("fixedChatId").asText());
        assertEquals("VERSION_CONFLICT",
                assertThrows(PredictionProblem.class, () -> service.update("10350", body)).code());
    }
}
```

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -o -Dtest=AlertRouteServiceTest test
```

Expected: FAIL，因为 `AlertRouteService` 不存在。

- [x] **Step 3: 实现配置服务和控制器**

`AlertRouteService` 必须：

```java
public ObjectNode get(String appId) {
    var stored = repository.config(appId);
    return stored == null ? defaults(appId) : stored.deepCopy();
}

public ObjectNode update(String appId, JsonNode body) {
    requireFields(body);
    var current = get(appId);
    if (body.path("expectedVersion").asInt() != current.path("version").asInt())
        throw problem(409, "VERSION_CONFLICT", "配置已变化，请刷新。");
    applyAlertFields(current, body);
    return repository.saveConfig(appId, current, body.path("expectedVersion").asInt(), clock.instant());
}
```

控制器路径：

```java
@RestController
@RequestMapping("/api/v1/alert-routes")
public class AlertRouteController {
    @GetMapping public ArrayNode list() { return service.listJson(); }
    @GetMapping("/{appId}") public ObjectNode get(@PathVariable String appId) { return service.get(appId); }
    @PutMapping("/{appId}") public ObjectNode update(@PathVariable String appId, @RequestBody JsonNode body) {
        return service.update(appId, body);
    }
}
```

`listJson()` 将 `list()` 的结果组装为 `ArrayNode`。校验值必须严格采用规格范围，`alertsEnabled` 默认 `false`，`p1AutoCreateGroup` 默认 `true`，`testChatId` 默认空且不得用于正式事故。

- [x] **Step 4: 运行测试**

Run:

```bash
cd backend
mvn -o -Dtest=AlertRouteServiceTest test
```

Expected: PASS。

- [ ] **Step 5: 提交（未执行，等待用户明确授权）**

```bash
git add backend/src/main/java/com/yukino/cdn/alert/AlertRouteService.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertRouteController.java \
  backend/src/test/java/com/yukino/cdn/alert/AlertRouteServiceTest.java
git commit -m "feat: add app scoped alert routes"
```

---

### Task 3: 飞书企业应用机器人客户端（实现完成，提交待授权）

**Files:**

- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertSettings.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertSender.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/FeishuOpenApiClient.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/FeishuOpenApiClientTest.java`

**Interfaces:**

- Produces: `String AlertSender.createChat(String name, String description)`。
- Produces: `void AlertSender.addMembers(String chatId, List<String> openIds)`。
- Produces: `String AlertSender.sendCard(String chatId, String cardJson, String idempotencyKey)`，并保留两参数重载。
- Produces: `void AlertSender.validateChat(String chatId)`。
- Produces: `void AlertSender.validateUsers(List<String> openIds)`。
- Produces: `void AlertSettings.verifyIngestToken(String token)`。
- Produces: `boolean AlertSettings.callbackMatches(String verificationToken)`。

- [x] **Step 1: 写本地 HTTP 替身测试**

```java
@Test void cachesTenantTokenAndSendsOpenApiRequestsWithoutLeakingCredentials() throws Exception {
    var requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/open-apis/auth/v3/tenant_access_token/internal", exchange -> {
        requests.incrementAndGet();
        reply(exchange, 200, "{\"code\":0,\"tenant_access_token\":\"tenant-token\",\"expire\":7200}");
    });
    server.createContext("/open-apis/im/v1/chats", exchange ->
            reply(exchange, 200, "{\"code\":0,\"data\":{\"chat_id\":\"oc_test\"}}"));
    server.start();
    try {
        var settings = new AlertSettings("app-id", "app-secret", "callback-token",
                "http://127.0.0.1:" + server.getAddress().getPort(), true);
        var client = new FeishuOpenApiClient(settings, new ObjectMapper(), Clock.systemUTC());
        assertEquals("oc_test", client.createChat("[P1] 10350-content-IMPORT_FAILED", "incident"));
        assertEquals("oc_test", client.createChat("[P1] second", "incident"));
        assertEquals(1, requests.get());
        assertFalse(settings.toString().contains("app-secret"));
        assertFalse(settings.toString().contains("tenant-token"));
    } finally { server.stop(0); }
}

private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
}
```

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -o -Dtest=FeishuOpenApiClientTest test
```

Expected: FAIL，因为客户端类不存在。

- [x] **Step 3: 实现企业应用机器人客户端**

环境变量：

```text
FEISHU_APP_ID
FEISHU_APP_SECRET
FEISHU_VERIFICATION_TOKEN
FEISHU_ENCRYPT_KEY
FEISHU_API_BASE_URL=https://open.feishu.cn
ALERT_INGEST_TOKEN
```

客户端最低接口：

```text
POST /open-apis/auth/v3/tenant_access_token/internal
POST /open-apis/im/v1/chats?user_id_type=open_id
POST /open-apis/im/v1/chats/{chat_id}/members?member_id_type=open_id
POST /open-apis/im/v1/messages?receive_id_type=chat_id
GET  /open-apis/im/v1/chats/{chat_id}
GET  /open-apis/contact/v3/users/{user_id}?user_id_type=open_id
```

发送卡片请求：

```java
var content = mapper.writeValueAsString(mapper.readTree(cardJson));
var body = mapper.createObjectNode()
        .put("receive_id", chatId)
        .put("msg_type", "interactive")
        .put("content", content);
```

`AlertSettings` 使用普通不可变类并显式覆盖 `toString()`，禁止输出 `appSecret`、verification token 和 ingest token。令牌缓存到过期前 60 秒；`401/99991663` 清除令牌并重试一次。HTTP 429 和明确 5xx 返回 retryable，超时返回 `UNKNOWN`。不得把 `app_secret`、令牌、完整响应写入异常文本。

- [x] **Step 4: 运行客户端测试**

Run:

```bash
cd backend
mvn -o -Dtest=FeishuOpenApiClientTest test
```

Expected: PASS。

- [ ] **Step 5: 提交（未执行，等待用户明确授权）**

```bash
git add backend/src/main/java/com/yukino/cdn/alert/AlertSettings.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertSender.java \
  backend/src/main/java/com/yukino/cdn/alert/FeishuOpenApiClient.java \
  backend/src/test/java/com/yukino/cdn/alert/FeishuOpenApiClientTest.java
git commit -m "feat: add Feishu application bot client"
```

---

### Task 4: 告警入站、事故去重与状态机（实现完成，提交待授权）

**Files:**

- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertEvent.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertIncidentRepository.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertIncidentService.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertController.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertApiAdvice.java`
- Create: `backend/src/test/java/com/yukino/cdn/alert/AlertTestFixtures.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/AlertIncidentServiceTest.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/AlertControllerTest.java`

**Interfaces:**

- Consumes: `AlertRouteService.get(appId)`。
- Produces: `ObjectNode AlertIncidentService.ingest(JsonNode body)`，返回 `incidentId/status/routingStatus/duplicate`。
- Produces: `ObjectNode AlertIncidentService.get(String incidentId)`。
- Produces: `ObjectNode AlertIncidentService.list(...)`。
- Produces: `ObjectNode AlertIncidentService.recover(String incidentId, String actor)`。
- Produces: `ObjectNode AlertTestFixtures.alert(...)`，供后续测试构造同一事件契约。

- [x] **Step 1: 写去重和恢复测试**

```java
@Test void sameFingerprintUpdatesOneIncidentAndRecoveryAllowsNewIncident() {
    var first = service.ingest(alert("evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1",
            "2026-10-22T06:03:12Z"));
    var second = service.ingest(alert("evt-2", "ALERT", "10350", "IMPORT_FAILED", "task:1",
            "2026-10-22T06:05:12Z"));
    assertEquals(first.path("incidentId").asText(), second.path("incidentId").asText());
    assertEquals(2, repo.get(first.path("incidentId").asText()).path("occurrenceCount").asInt());
    service.recover(first.path("incidentId").asText(), "ou_owner");
    var third = service.ingest(alert("evt-3", "ALERT", "10350", "IMPORT_FAILED", "task:1",
            "2026-10-22T06:10:12Z"));
    assertNotEquals(first.path("incidentId").asText(), third.path("incidentId").asText());
}
```

`AlertTestFixtures.alert(sourceEventId, eventKind, appId, incidentType, objectKey, occurredAt)`
使用 `ObjectMapper` 构造完整 `Event` JSON，包含 `title/observedValue/threshold/summary/evidence/detailUrl`，不得返回 Mock 发送结果。

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -o -Dtest=AlertIncidentServiceTest,AlertControllerTest test
```

Expected: FAIL，因为告警服务不存在。

- [x] **Step 3: 实现事件校验和事故状态机**

指纹：

```java
long bucket = event.occurredAt().getEpochSecond()
        / (route.path("activeWindowMinutes").asLong() * 60);
String fingerprint = sha256(event.appId() + "\n" + event.incidentType()
        + "\n" + event.objectKey() + "\n" + bucket);
```

入站规则：

- `ALERT` 先查 `(app_id, source_event_id)`，命中即返回原事故。
- 未命中时查 `active_fingerprint=fingerprint`；存在则更新 `last_seen_at_ms`、次数和 `details_json`。
- 不存在则插入 `status=OPEN`、`routing_status=PENDING`、`active_fingerprint=fingerprint`、`occurrence_count=1`，并设置 `next_reminder_at_ms=first_seen_at_ms+ackDeadlineMinutes`。
- `RECOVERY` 必须带 `incidentId`；仅允许 `OPEN/ACKED/PROCESSING -> RECOVERED`。
- 恢复时设置 `recovered_at_ms`、`active_fingerprint=NULL`、`next_reminder_at_ms=NULL`。

控制器：

```java
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode ingest(@RequestHeader("X-Alert-Token") String token, @RequestBody JsonNode body) {
        settings.verifyIngestToken(token);
        return service.ingest(body);
    }
}
```

`RECOVERY` 幂等；重复恢复返回原结果，不重复触发后续动作。

- [x] **Step 4: 运行告警服务与控制器测试**

Run:

```bash
cd backend
mvn -o -Dtest=AlertIncidentServiceTest,AlertControllerTest test
```

Expected: PASS；202 测试只断言接收和 `incidentId`，不断言飞书发送。

- [ ] **Step 5: 提交（未执行，等待用户明确授权）**

```bash
git add backend/src/main/java/com/yukino/cdn/alert/AlertEvent.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertIncidentRepository.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertIncidentService.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertController.java \
  backend/src/test/java/com/yukino/cdn/alert/AlertTestFixtures.java \
  backend/src/test/java/com/yukino/cdn/alert/AlertIncidentServiceTest.java \
  backend/src/test/java/com/yukino/cdn/alert/AlertControllerTest.java
git commit -m "feat: ingest and deduplicate alerts"
```

---

### Task 5: 群路由、飞书卡片和投递审计（实现完成，提交待授权）

**Files:**

- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertDeliveryRepository.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertCardRenderer.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertDispatcher.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertRouteActionService.java`
- Modify: `backend/src/main/java/com/yukino/cdn/alert/AlertRouteController.java`
- Modify: `backend/src/main/java/com/yukino/cdn/alert/AlertIncidentService.java`
- Create: `backend/src/test/java/com/yukino/cdn/alert/FakeAlertSender.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/AlertDispatcherTest.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/AlertRouteValidationTest.java`

**Interfaces:**

- Consumes: `AlertSender`。
- Consumes: `AlertIncidentRepository`。
- Produces: `void AlertDispatcher.open(JsonNode incident, JsonNode route)`。
- Produces: `void AlertDispatcher.update(JsonNode incident)`。
- Produces: `void AlertDispatcher.recover(JsonNode incident)`。
- Produces: `void AlertRouteActionService.validate(String appId)` 和 `void AlertRouteActionService.test(String appId)`。

- [x] **Step 1: 写 P1/P2/P3 路由测试**

```java
@Test void p1CreatesOneIncidentGroupWhileP2UsesFixedGroup() {
    var sender = new FakeSender();
    var dispatcher = dispatcher(sender);
    var p1 = incident("inc-1", "P1", "OPEN");
    dispatcher.open(p1, route("10350", "oc_fixed_10350"));
    dispatcher.update(p1);
    assertEquals(1, sender.createdChats);
    assertEquals(List.of("ou_owner", "ou_backup"), sender.invited);
    assertEquals("oc_created", sender.lastChatId);

    var p2 = incident("inc-2", "P2", "OPEN");
    dispatcher.open(p2, route("10066", "oc_fixed_10066"));
    assertEquals(1, sender.createdChats);
    assertEquals("oc_fixed_10066", sender.lastChatId);
}

@Test void p1FallsBackToFixedGroupWithoutCreatingASecondGroupLater() {
    var sender = new FakeSender();
    sender.createFails = true;
    var incident = incident("inc-3", "P1", "OPEN");
    dispatcher(sender).open(incident, route("10350", "oc_fixed_10350"));
    assertEquals("oc_fixed_10350", sender.lastChatId);
    assertEquals("GROUP_CREATE_FAILED", repo.get("inc-3").path("routingError").asText());
    dispatcher(sender).update(repo.get("inc-3"));
    assertEquals(1, sender.createAttempts);
}
```

`FakeAlertSender` 实现 `AlertSender`，公开 `createdChats/createAttempts/invited/lastChatId/createFails/sentCards/lastCard/reminderCards`，只记录调用，不连接网络。

```java
@Test void routeTestUsesOnlyTheConfiguredTestChat() {
    var action = new AlertRouteActionService(routeService, sender);
    action.test("10350");
    assertEquals("oc_test_10350", sender.lastChatId);
    assertTrue(sender.lastCard.contains("DEMO"));
    assertEquals("TEST_CHAT_REQUIRED",
            assertThrows(PredictionProblem.class, () -> action.test("10066")).code());
    assertEquals(1, sender.sentCards);
}
```

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -o -Dtest=AlertDispatcherTest,AlertRouteValidationTest test
```

Expected: FAIL，因为 `AlertDispatcher` 和测试发送路由不存在。

- [x] **Step 3: 实现路由和卡片**

P1 首次事故：

```java
if (incident.chatId() == null && route.p1AutoCreateGroup()) {
    try {
        String chatId = sender.createChat(groupName, description);
        sender.addMembers(chatId, memberIds);
        incident.setChat("INCIDENT_GROUP", chatId);
    } catch (RuntimeException failure) {
        incident.setRoutingError("GROUP_CREATE_FAILED");
        incident.setChat("FIXED_GROUP", route.fixedChatId());
    }
}
```

卡片固定包含：

```text
[等级] AppID · 业务名
事故标题
事故号、状态、开始时间、异常对象
当前值、阈值、影响摘要
负责人、认领时限
认领并处理、查看事故详情
```

每发送一步写入 `notification_delivery`：

```text
GROUP_CREATE / MEMBER_INVITE / OPEN / UPDATE / RECOVERY
```

群创建失败后的救援消息、后续更新和恢复都必须使用同一固定群。超时或未确认结果写 `UNKNOWN`，不自动重发。

配置动作：

- `POST /api/v1/alert-routes/{appId}/validate` 验证 `fixedChatId`、`testChatId` 和全部负责人；只读，不发消息。
- `POST /api/v1/alert-routes/{appId}/test` 只向 `testChatId` 发送一条 DEMO 测试卡片。
- 未配置 `testChatId` 返回 422 `TEST_CHAT_REQUIRED`，禁止改发固定群。

- [x] **Step 4: 运行调度器测试**

Run:

```bash
cd backend
mvn -o -Dtest=AlertDispatcherTest,AlertRouteValidationTest test
```

Expected: PASS。

- [ ] **Step 5: 提交（未执行，等待用户明确授权）**

```bash
git add backend/src/main/java/com/yukino/cdn/alert/AlertDeliveryRepository.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertCardRenderer.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertDispatcher.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertRouteActionService.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertRouteController.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertIncidentService.java \
  backend/src/test/java/com/yukino/cdn/alert/FakeAlertSender.java \
  backend/src/test/java/com/yukino/cdn/alert/AlertDispatcherTest.java \
  backend/src/test/java/com/yukino/cdn/alert/AlertRouteValidationTest.java
git commit -m "feat: route incident alerts to Feishu groups"
```

---

### Task 6: 认领、未处理提醒与恢复停止机制（实现完成，提交待授权）

**Files:**

- Create: `backend/src/main/java/com/yukino/cdn/alert/AlertReminderScheduler.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/FeishuCallbackVerifier.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/FeishuCardActionController.java`
- Create: `backend/src/main/java/com/yukino/cdn/alert/FeishuCardActionApiAdvice.java`
- Modify: `backend/src/main/java/com/yukino/cdn/alert/AlertIncidentService.java`
- Modify: `backend/src/main/java/com/yukino/cdn/alert/AlertIncidentRepository.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/AlertReminderSchedulerTest.java`
- Test: `backend/src/test/java/com/yukino/cdn/alert/FeishuCardActionControllerTest.java`

**Interfaces:**

- Produces: `void AlertReminderScheduler.tick()`。
- Produces: `ObjectNode AlertIncidentService.ack(String incidentId, String openId, int expectedVersion)`。
- Produces: `ObjectNode AlertIncidentService.processing(String incidentId, String openId, int expectedVersion)`。
- Produces: `ObjectNode AlertIncidentService.recover(String incidentId, String openId)`。

- [x] **Step 1: 写提醒和停止提醒测试**

```java
@Test void reminderIsSentOncePerIntervalAndStopsAfterRecovery() {
    var incident = service.ingest(AlertTestFixtures.alert(
            "evt-1", "ALERT", "10350", "IMPORT_FAILED", "task:1",
            "2026-10-22T06:00:00Z"));
    String incidentId = incident.path("incidentId").asText();
    clock.now = Instant.parse("2026-10-22T06:05:00Z");
    scheduler.tick();
    assertEquals(1, sender.reminderCards);
    scheduler.tick();
    assertEquals(1, sender.reminderCards);
    clock.now = Instant.parse("2026-10-22T06:20:00Z");
    scheduler.tick();
    assertEquals(2, sender.reminderCards);
    service.recover(incidentId, "ou_owner");
    clock.now = Instant.parse("2026-10-22T07:00:00Z");
    scheduler.tick();
    assertEquals(2, sender.reminderCards);
}
```

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -o -Dtest=AlertReminderSchedulerTest,FeishuCardActionControllerTest test
```

Expected: FAIL，因为调度器和回调控制器不存在。

- [x] **Step 3: 实现提醒与操作**

提醒查询条件：

```sql
WHERE status='OPEN'
  AND acknowledged_at_ms IS NULL
  AND next_reminder_at_ms IS NOT NULL
  AND next_reminder_at_ms<=:now
  AND reminder_count<:maxReminderCount
```

使用版本或 `next_reminder_at_ms` CAS 认领提醒，成功后：

```java
incident.reminderCount++;
incident.escalatedAt = incident.escalatedAt == null ? now : incident.escalatedAt;
incident.nextReminderAt = now.plusMillis(route.remindIntervalMinutes() * 60_000L);
```

认领后：

```java
status = ACKED;
acknowledgedBy = trustedOpenId;
acknowledgedAt = now;
nextReminderAt = null;
```

恢复后：

```java
status = RECOVERED;
recoveredAt = now;
nextReminderAt = null;
activeFingerprint = null;
```

第一版不新增网页认领、处理中或恢复写接口。`ack/processing/recover` 只由验证后的飞书卡片回调调用；操作人 `open_id` 只取自验证后的回调数据。网页请求体中的 `openId` 不得作为身份依据。

- [x] **Step 4: 运行提醒与回调测试**

Run:

```bash
cd backend
mvn -o -Dtest=AlertReminderSchedulerTest,FeishuCardActionControllerTest test
```

Expected: PASS；重复 tick 和重复恢复均不重复发送。

- [ ] **Step 5: 提交（未执行，等待用户明确授权）**

```bash
git add backend/src/main/java/com/yukino/cdn/alert/AlertReminderScheduler.java \
  backend/src/main/java/com/yukino/cdn/alert/FeishuCallbackVerifier.java \
  backend/src/main/java/com/yukino/cdn/alert/FeishuCardActionController.java \
  backend/src/main/java/com/yukino/cdn/alert/FeishuCardActionApiAdvice.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertIncidentService.java \
  backend/src/main/java/com/yukino/cdn/alert/AlertIncidentRepository.java \
  backend/src/test/java/com/yukino/cdn/alert/AlertReminderSchedulerTest.java \
  backend/src/test/java/com/yukino/cdn/alert/FeishuCardActionControllerTest.java
git commit -m "feat: add incident acknowledgements and reminders"
```

---

### Task 7: 通知中心前端闭环

**Files:**

- Create: `frontend/src/alerts.js`
- Create: `frontend/src/components/alerts/IncidentPanel.vue`
- Create: `frontend/src/components/alerts/AlertRoutePanel.vue`
- Create: `frontend/src/components/alerts/AlertDeliveryPanel.vue`
- Modify: `frontend/src/views/NotificationView.vue`
- Test: `frontend/tests/alerts.test.js`

**Interfaces:**

- Consumes backend APIs listed in the approved spec。
- Produces: `alertApi.listIncidents/getIncident`，页面写操作仅限配置和显式失败重试。
- Produces: `alertApi.listRoutes/getRoute/updateRoute/validateRoute/testRoute`。

- [ ] **Step 1: 写前端 API 测试**

```javascript
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { alertApi } from '../src/alerts.js'

test('alert route update carries optimistic version', async () => {
  const calls = []
  const fetchService = async (url, options) => {
    calls.push({ url, options })
    return { ok: true, status: 200, json: async () => ({ appId: '10350', version: 1 }) }
  }
  await alertApi.updateRoute('10350', { expectedVersion: 0, alertsEnabled: true }, fetchService)
  assert.match(calls[0].url, /alert-routes\/10350/)
  assert.equal(calls[0].options.method, 'PUT')
  assert.equal(JSON.parse(calls[0].options.body).expectedVersion, 0)
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd frontend
npm test -- --test-name-pattern="alert route"
```

Expected: FAIL，因为 `frontend/src/alerts.js` 不存在。

- [ ] **Step 3: 实现 API 模块和三个页面区域**

`alerts.js` 必须：

- 编码所有筛选参数。
- GET 没有请求体。
- PUT/POST 明确 `Content-Type: application/json`。
- 事故操作携带 `expectedVersion`。
- 告警入站接口不暴露给前端，也不把 `X-Alert-Token` 放入浏览器代码。

`NotificationView.vue` 增加三个页签：

```text
实时告警
业务通知配置
发送记录
```

“业务通知配置”支持新增 AppID、编辑固定群、负责人、备份负责人、P1 自动建群、提醒时限和总开关。保存后必须使用服务端返回的新版本。

“实时告警”显示事故状态、等级、负责人、提醒次数、群和投递结果；提供“打开飞书群/卡片”链接。当前仓库没有登录用户体系，页面不得提交自报 `openId`，认领、处理中更新和恢复均在飞书卡片完成。

“发送记录”保留现有每日/逾期通知能力，并增加事故阶段、`chatId`、`messageId` 和错误码。

- [ ] **Step 4: 运行前端测试和构建**

Run:

```bash
cd frontend
npm test
npm run build
```

Expected: 测试 PASS，Vite build 成功。

- [ ] **Step 5: 提交（仅在用户明确授权提交后执行）**

```bash
git add frontend/src/alerts.js \
  frontend/src/components/alerts/IncidentPanel.vue \
  frontend/src/components/alerts/AlertRoutePanel.vue \
  frontend/src/components/alerts/AlertDeliveryPanel.vue \
  frontend/src/views/NotificationView.vue \
  frontend/tests/alerts.test.js
git commit -m "feat: add incident alert center UI"
```

---

### Task 8: 全链路验证与验收清单

**Files:**

- Create: `backend/src/test/java/com/yukino/cdn/alert/AlertFlowIntegrationTest.java`
- Create: `docs/superpowers/acceptance/2026-10-22-feishu-incident-alert-checklist.md`
- Modify: `backend/src/test/java/com/yukino/cdn/alert/AlertDispatcherTest.java`

**Interfaces:**

- Consumes: Tasks 1-7 的完整后端和前端接口。
- Produces: 可追踪的自动测试、浏览器验收和真实飞书验收边界。

- [ ] **Step 1: 写全链路失败测试**

```java
@Test void p1IncidentCreatesOneGroupAndRecoveryStopsReminders() {
    var first = service.ingest(alert("evt-1", "ALERT", "10350", "P1"));
    var update = service.ingest(alert("evt-2", "ALERT", "10350", "P1"));
    assertEquals(first.path("incidentId").asText(), update.path("incidentId").asText());
    assertEquals(1, sender.createAttempts);
    service.recover(first.path("incidentId").asText(), "ou_owner");
    clock.now = clock.now.plusSeconds(3600);
    reminderScheduler.tick();
    assertEquals(0, sender.reminderCards);
}
```

- [ ] **Step 2: 运行集成测试并确认失败**

Run:

```bash
cd backend
mvn -o -Dtest=AlertFlowIntegrationTest test
```

Expected: FAIL，因为集成夹具尚未完成。

- [ ] **Step 3: 完成集成夹具和验收清单**

自动测试必须覆盖：

- P1 建群一次，P2/P3 不建群。
- 两个 AppID 使用不同固定群。
- 同一指纹只创建一个活跃事故。
- 后续异常不重复建群。
- 认领后停止提醒。
- 恢复后发送一张恢复卡并停止提醒。
- 网络未知不会产生虚假 `SENT`。
- 凭证不出现在响应、记录或错误文本中。

验收清单必须把以下证据分开记录：

```text
1. H2/HTTP fixture
2. Java unit/controller/integration
3. Vue unit/build
4. Browser acceptance
5. Real Feishu test group
6. Manual production MySQL migration
```

- [ ] **Step 4: 运行完整验证**

Run:

```bash
cd backend
mvn -o test
cd ../frontend
npm test
npm run build
cd ..
git diff --check
```

Expected:

- Maven 全部 PASS。
- Node 全部 PASS。
- Vite build PASS。
- `git diff --check` 无输出。

浏览器验收在实际启动 Java、Vue 后执行，必须验证新增 AppID、两个 AppID 隔离、飞书群/卡片链接、恢复和失败状态展示。认领与恢复身份由飞书卡片回调验证，不从网页请求体接收。真实飞书测试需用户另行授权，不能由 fixture 代替。

- [ ] **Step 5: 提交（仅在用户明确授权提交后执行）**

```bash
git add backend/src/test/java/com/yukino/cdn/alert/AlertFlowIntegrationTest.java \
  backend/src/test/java/com/yukino/cdn/alert/AlertDispatcherTest.java \
  docs/superpowers/acceptance/2026-10-22-feishu-incident-alert-checklist.md
git commit -m "test: verify incident alert delivery lifecycle"
```

---

## Spec Coverage

| Spec 要求 | 对应 Task |
|---|---|
| AppID 业务通知配置 | Task 2、Task 7 |
| P1 自动建群和固定群兜底 | Task 3、Task 5 |
| P2/P3 固定群 | Task 5 |
| 同指纹事故去重 | Task 4 |
| 后续异常更新原事故 | Task 4、Task 5 |
| 恢复卡片和停止提醒 | Task 5、Task 6 |
| 结构化线上事故信息 | Task 4、Task 5 |
| 认领与漏处理提醒 | Task 6 |
| 投递审计与失败边界 | Task 5、Task 8 |
| 增量迁移 | Task 1 |
| 页面配置和实时告警 | Task 7 |
| 真实飞书与浏览器边界 | Task 8 |

## Execution Gate

计划执行时，每次只启动一个 Task。Task 结束后必须汇报：

- 修改文件。
- 失败测试证据。
- 通过测试证据。
- 未执行的浏览器、真实飞书或 MySQL 操作。
- 下一步建议。

等待用户明确批准后再进入下一 Task。
