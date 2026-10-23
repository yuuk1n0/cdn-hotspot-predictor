# 飞书事故告警与漏处理提醒设计

日期：2026-10-22  
状态：已确认，待文档评审  
范围：Java 通知后端、Vue 通知中心、飞书企业应用机器人、MySQL 增量迁移

## 1. 目标

将现有“手动预览待压缩清单并通过固定 Webhook 发送”升级为按 AppID 路由的事故告警闭环：

```text
数据告警 -> 创建或更新事故 -> 选择飞书群 -> 通知负责人
        -> 超时未认领提醒/升级 -> 恢复卡片 -> 停止提醒
```

核心结果：

- 页面可维护不同 AppID 的业务名称、负责人、备份负责人、固定应急群和提醒时限。
- P1 事故自动创建独立事故群并拉入负责人和备份负责人。
- P2/P3 事故发送到该 AppID 绑定的固定应急群。
- 同一 `appId + 事故类型 + 对象 + 活跃窗口` 只产生一个活跃事故。
- 后续异常更新原事故，并在原群继续通知，不重复建群。
- 事故恢复后，在同一群发送恢复卡片，并停止未处理提醒。
- 消息包含足以启动修复的线上事故信息，而不仅是摘要文本。

## 2. 现状与约束

当前实现已具备通知预览、Webhook 投递记录、重试、每日清单和逾期检查，但存在以下边界：

- `NotificationSettings` 只接受固定机器人 Webhook，不能创建群或拉成员。
- `FeishuWebhookClient` 只发送文本消息，不能发送交互卡片或处理认领动作。
- `NotificationView.vue` 的 App 列表来自前端静态 `appOptions`，不是可维护业务配置。
- 现有通知类型只有 `DAILY` 和 `OVERDUE`，没有事故状态、负责人和升级流程。
- `predictor/app/alerts.py` 目前只上报导入失败，且目标在环境中固定。

现有 Webhook 继续服务普通通知。事故建群、拉人和交互卡片必须新增飞书企业应用机器人通道，不能继续只用自定义群机器人 Webhook。

## 3. 已确认规则

### 3.1 路由

| 等级 | 群策略 |
|---|---|
| P1 | 自动创建独立事故群，拉入负责人和备份负责人；建群失败时发送到固定应急群 |
| P2/P3 | 发送到 AppID 绑定的固定应急群 |

P1 建群失败后，事故记录 `GROUP_CREATE_FAILED`，告警优先发到固定应急群。该事故后续不自动补建群，避免同一事故出现多个群。

### 3.2 去重与更新

事故指纹：

```text
sha256(appId + incidentType + objectKey + activeWindow)
```

- `objectKey` 表示受影响对象，例如文件标识、服务、接口或任务编号。
- `activeWindow` 为 `floor(occurredAt / activeWindowMinutes)`，同一事件重试必须落回同一窗口。
- 活跃窗口采用固定时间桶，不随更新消息滑动延长；跨过时间桶边界的新告警属于新活跃窗口。
- 相同指纹且事故状态为 `OPEN/ACKED/PROCESSING` 时，只更新已有事故。
- 更新内容包括最后发生时间、次数、当前值、阈值和证据链接。
- 更新消息发送到原事故群和原消息话题；原事故没有可复用群时，发送到该 AppID 的固定应急群。
- 事故恢复后，同一指纹再次告警视为新事故。

### 3.3 恢复

- 恢复事件可以把事故从 `OPEN/ACKED/PROCESSING` 转为 `RECOVERED`。
- 恢复卡片发送到原事故群。
- 恢复后停止未认领提醒和升级提醒。
- 不自动解散或删除飞书群，群生命周期由人工管理。

## 4. 页面设计

“通知中心”保留为一级页面，拆为三个页签。

### 4.1 实时告警

列表字段：

- 等级、状态、AppID、业务名称。
- 事故类型、异常对象、当前值、阈值。
- 首次发生时间、最后发生时间、持续时间。
- 负责人、备份负责人、是否超时、提醒次数。
- 飞书群类型、群名称、卡片的 `SENT/FAILED/UNKNOWN` 状态。

操作：

- 查看事故详情和证据。
- 打开飞书群。
- 页面查看认领和处理状态；第一版通过飞书卡片完成认领、处理中更新和恢复。
- 对投递失败记录执行明确的手工重试。

### 4.2 业务通知配置

每个 AppID 至少配置：

| 字段 | 必填 | 说明 |
|---|---|---|
| `appId` | 是 | 业务唯一标识 |
| `businessName` | 是 | 页面和卡片显示的业务名称 |
| `ownerOpenIds` | 是 | P1/P2/P3 主负责人 |
| `backupOpenIds` | P1 是 | 超时升级和自动建群成员 |
| `fixedChatId` | 是 | P2/P3 固定群，也是 P1 建群失败兜底群 |
| `testChatId` | 否 | 仅用于配置验证和测试卡片，不接收正式告警 |
| `p1AutoCreateGroup` | 是 | 是否启用 P1 自动建群 |
| `ackDeadlineMinutes` | 是 | 超过该时限仍未认领即提醒 |
| `remindIntervalMinutes` | 是 | 未处理时的重复提醒间隔 |
| `maxReminderCount` | 是 | 单次事故最大提醒次数 |
| `activeWindowMinutes` | 是 | 同指纹合并窗口 |
| `alertsEnabled` | 是 | 总开关，默认关闭 |

配置保存必须带 `expectedVersion`，避免多人编辑互相覆盖。负责人和群必须能通过飞书接口验证；无法验证的配置不能直接启用正式告警。

`POST /api/v1/alert-routes/{appId}/test` 必须且只能使用 `testChatId`；未配置测试群时返回 `TEST_CHAT_REQUIRED`，不得改发 `fixedChatId`。

时限范围：`ackDeadlineMinutes` 和 `remindIntervalMinutes` 为 1 至 1440，`maxReminderCount` 为 1 至 50，`activeWindowMinutes` 为 1 至 10080。`p1AutoCreateGroup` 默认开启；只有受控灰度或测试环境可以关闭。

### 4.3 发送记录

在现有投递记录基础上增加：

- `incidentId`、事故阶段 `GROUP_CREATE/MEMBER_INVITE/OPEN/UPDATE/REMINDER/RECOVERY`。
- 群创建、成员邀请、卡片发送三个独立结果。
- 飞书 `chatId`、`messageId`、错误码和人工重试记录。

`PENDING` 只表示后端已接收。只有确认收到飞书成功响应后才显示 `SENT`。

## 5. 告警事件

告警来源包括导入失败、预测任务失败、数据质量异常、模型调用异常及其他监控源。统一入口接收结构化事件：

```json
{
  "sourceEventId": "monitor-20261022-0001",
  "eventKind": "ALERT",
  "appId": "10350",
  "severity": "P1",
  "incidentType": "IMPORT_FAILED",
  "objectKey": "importTaskId:imp_xxx",
  "title": "CSV 导入连续失败",
  "occurredAt": "2026-10-22T06:03:12Z",
  "observedValue": "3 次",
  "threshold": "1 次",
  "summary": "最近 10 分钟内连续失败 3 次",
  "evidence": {
    "importTaskId": "imp_xxx",
    "runId": null,
    "errorCode": "INVALID_CSV_HEADER"
  },
  "detailUrl": "/imports?taskId=imp_xxx"
}
```

约束：

- `sourceEventId` 必填，同一来源事件重复提交返回同一事故，不重复建群或发送。
- `eventKind` 仅允许 `ALERT/RECOVERY`。
- `RECOVERY` 事件必须携带初始 `ALERT` 响应返回的 `incidentId`；事故已恢复时幂等返回原结果。
- `appId` 必须存在有效通知配置。
- `severity` 仅允许 `P1/P2/P3`。
- `occurredAt` 使用带时区的 ISO-8601 时间。
- `detailUrl` 只接受站内相对路径或受信任监控域名。
- 没有有效配置时仍保存事故，`status=OPEN`、`routingStatus=ROUTING_FAILED`，页面明确展示，不伪造发送成功。

`POST /api/v1/alerts/events` 返回 HTTP 202 和 `incidentId`。202 只表示告警事件已被后端接收，不表示飞书群或消息发送成功；最终结果以 `GET /api/v1/alerts/{incidentId}` 的投递链路为准。

## 6. 飞书消息

事故卡片最少包含：

```text
[P1] 10350 · 内容分发
CSV 导入连续失败

事故号：INC-20261022-001
状态：OPEN / 待认领
开始时间：2026-10-22 14:03:12
异常对象：imp_xxx
当前值：3 次        阈值：1 次
影响：最近 10 分钟内连续失败 3 次
负责人：张三
认领时限：14:08:12

[认领并处理] [查看事故详情]
```

卡片要求：

- 标题不含客户敏感信息或完整文件路径。
- 负责人通过飞书用户 ID 被真实 `@`，不能只在正文写姓名。
- 卡片按钮回调由后端验证飞书签名和用户身份。
- 只有配置的负责人、备份负责人或管理员可以认领。
- 更新时间卡片显示异常次数和最新值。
- 恢复卡片显示恢复时间、持续时长、恢复前最后状态。
- 群名称格式为 `[P1] {appId}-{businessName}-{incidentType}`，不含文件标识和错误详情。

## 7. 状态与提醒

事故状态：

```text
OPEN -> ACKED -> PROCESSING -> RECOVERED
```

`ESCALATED` 是提醒标记，不是独立事故状态。实际操作顺序：

1. 告警到达，创建或更新事故。
2. 创建/绑定群并发送事故卡片。
3. 超过 `ackDeadlineMinutes` 仍未认领，发送提醒并 `@` 负责人和备份负责人，记录 `escalatedAt`。
4. 之后按 `remindIntervalMinutes` 发送未处理提醒，最多 `maxReminderCount` 次。
5. 负责人认领后停止未认领提醒。
6. 事故恢复后发送恢复卡片，并停止全部后续提醒。
7. 重启后从数据库时间继续执行，不依赖内存定时状态。

若同一次事故同时存在多个并发提醒，使用 `incidentId + phase + sequence` 唯一键保证只发送一次。

## 8. 架构与职责

```text
告警来源
  -> POST /api/v1/alerts/events
  -> AlertIncidentService
     -> 读取 App 路由配置
     -> 创建/更新事故
     -> IncidentGroupService
        -> FeishuOpenApiClient
           -> 创建群/验证群
           -> 拉负责人/备份负责人
           -> 发送交互卡片
     -> NotificationDeliveryRepository
  -> AlertReminderScheduler
     -> 未认领提醒/恢复检查
  -> Vue 通知中心
```

模块边界：

- `AlertIncidentService`：事故去重、状态机、恢复。
- `AlertRouteService`：AppID 配置校验和路由决策。
- `FeishuOpenApiClient`：租户令牌、建群、拉成员、发消息、回调校验。
- `AlertReminderScheduler`：认领超时、重复提醒和恢复后停止提醒。
- `NotificationRepository`：投递记录、重试和审计，不直接决定事故状态。

Vue 页面只调用后端 API，不直接请求飞书。

## 9. 数据设计

采用增量迁移 `006_incident_alerts.sql`，执行前必须人工确认目标 MySQL；禁止应用启动时自动执行。

### 9.1 `app_config`

复用现表，在 `settings_json` 中增加：

```json
{
  "alertsEnabled": false,
  "businessName": "内容分发",
  "ownerOpenIds": [],
  "backupOpenIds": [],
  "fixedChatId": "",
  "testChatId": "",
  "p1AutoCreateGroup": true,
  "ackDeadlineMinutes": 5,
  "remindIntervalMinutes": 15,
  "maxReminderCount": 4,
  "activeWindowMinutes": 60
}
```

不保存 `appSecret`、租户令牌或任意 Webhook 明文。

### 9.2 `alert_event`

用于接收幂等：

- `source_event_id`：告警来源内唯一事件号。
- `event_kind`：`ALERT/RECOVERY`。
- `app_id`、`incident_id`、`received_at_ms`。
- `payload_json`：规范化后的最小事件事实。
- 唯一键为 `(app_id, source_event_id)`。

同一来源事件即使已经触发过恢复，重复提交也只返回原事故，不会重新打开事故或再次发送。

### 9.3 `alert_incident`

主要字段：

- `id`：事故号，主键。
- `app_id`、`severity`、`incident_type`、`object_key`。
- `fingerprint`：事故指纹。
- `status`：`OPEN/ACKED/PROCESSING/RECOVERED`。
- `routing_status`：`PENDING/ROUTED/ROUTING_FAILED`。
- `routing_error`：建群、验证群或路由失败时的脱敏错误码。
- `active_fingerprint`：事故活跃时为 `fingerprint`，恢复时置空。
- `chat_mode`：`INCIDENT_GROUP/FIXED_GROUP`。
- `chat_id`、`opening_message_id`。
- `first_seen_at_ms`、`last_seen_at_ms`、`ack_deadline_at_ms`。
- `acknowledged_at_ms`、`acknowledged_by`、`recovered_at_ms`。
- `reminder_count`、`next_reminder_at_ms`、`escalated_at_ms`。
- `details_json`：当前值、阈值、摘要、证据和详情链接。
- `version`：并发更新版本。

`active_fingerprint` 使用可空唯一索引。相同指纹只允许一个未恢复事故；恢复后置空，允许新事故使用同一指纹。

### 9.4 `notification_delivery`

增加可空字段：

- `incident_id`
- `alert_phase`：`GROUP_CREATE/MEMBER_INVITE/OPEN/UPDATE/REMINDER/RECOVERY`
- `chat_id`
- `feishu_message_id`

旧通知记录保持兼容。

## 10. API

| Method + Path | 用途 |
|---|---|
| `POST /api/v1/alerts/events` | 接收结构化告警，要求来源鉴权头和 `sourceEventId` |
| `GET /api/v1/alerts` | 按 App、等级、状态、时间分页查询事故 |
| `GET /api/v1/alerts/{incidentId}` | 查看事故、证据和投递链路 |
| `GET /api/v1/alert-routes` | 查询 App 告警配置 |
| `GET /api/v1/alert-routes/{appId}` | 查询单个 App 配置 |
| `PUT /api/v1/alert-routes/{appId}` | 按版本更新配置 |
| `POST /api/v1/alert-routes/{appId}/validate` | 验证负责人、固定群和机器人能力 |
| `POST /api/v1/alert-routes/{appId}/test` | 仅向明确配置的测试群发送测试卡片 |
| `POST /api/v1/feishu/card-actions` | 接收飞书卡片按钮回调 |

所有写接口要求幂等键或业务唯一键。更新配置和事故状态要求版本检查。

## 11. 异常处理

- 配置缺失或负责人为空：事故 `routingStatus=ROUTING_FAILED`，不创建群、不声称已通知。
- 租户令牌失效：刷新令牌后重试一次；仍失败则记录 `FEISHU_AUTH_FAILED`。
- 建群成功、拉人部分失败：保留群，发送卡片，同时记录成员邀请失败并提示人工补齐。
- 建群失败：发送固定应急群；事故不再自动补建群。
- 卡片发送 HTTP 429 或明确 5xx：按 1/5/15 分钟退避，最多 4 次尝试。
- 请求超时或响应无法确认：`UNKNOWN`，不自动重复发送。
- 固定群失效或机器人被移出：`CHAT_UNAVAILABLE`，实时告警页面显示并停止自动重试。
- 卡片回调签名无效或用户无权限：拒绝操作并记录审计事件。
- 网页尝试认领、处理中更新或恢复：第一版返回 `WEB_IDENTITY_UNAVAILABLE`，并提示在飞书卡片中操作。
- 重复恢复事件：返回已有恢复结果，不重复发送恢复卡片。

## 12. 安全

- 飞书应用凭证只来自服务端环境变量，不进入数据库、接口响应或日志。
- 固定群 `chatId` 和负责人 ID 可以做格式校验，但不对普通用户暴露凭证。
- 告警入口要求来源鉴权，并要求 `sourceEventId` 幂等。
- 卡片回调校验飞书签名，操作人身份取自可信回调数据。
- 当前仓库没有登录用户或统一身份上下文，因此第一版不从网页请求体接收 `openId`，也不开放网页写操作。
- 群名、卡片标题不包含客户数据、完整文件路径、令牌或监控密钥。
- 测试发送只能使用 `demoOnly=true` 的测试目标。

## 13. 测试与验收

### 13.1 自动测试

- 相同 `appId + incidentType + objectKey + activeWindow` 返回同一活跃事故。
- 两个 AppID 相同事故类型和对象不会互相合并。
- P1 只创建一个事故群。
- P2/P3 不建群，只使用各自固定群。
- 同一事故后续异常不新建群，更新原事故并向原群发送更新。
- 已恢复指纹再次告警创建新事故。
- 未认领超时提醒只发送一次；重复调度不重复发送。
- 认领后停止未认领提醒。
- 恢复后发送一张恢复卡片并停止全部提醒。
- 重复回调、重复恢复和网络超时不会产生虚假 `SENT`。

### 13.2 集成与页面验收

- 页面新增两个 AppID，分别配置负责人、固定群和时限。
- 两个 AppID 的告警不会串群。
- P1 事故群真实包含负责人、备份负责人和事故卡片。
- P2/P3 告警进入各自固定群。
- 页面可查看事故链路、认领状态、提醒次数和恢复状态。
- 发送失败显示具体阶段和错误码。

### 13.3 真实环境边界

以下证据必须分开报告：

- H2 或 HTTP 替身测试。
- Java 服务集成测试。
- Vue 构建与浏览器验收。
- 真实飞书应用权限、测试群、正式群发送。
- 生产 MySQL 迁移执行。

Fixture 测试不能替代真实飞书建群、拉人、卡片按钮和正式环境验收。

## 14. 实施顺序

1. 完成并人工确认 `006_incident_alerts.sql` 的影响范围。
2. 实现飞书企业应用机器人通道和测试群验证。
3. 实现告警入口、事故去重、状态机和提醒调度。
4. 实现 P1 建群、P2/P3 固定群、更新卡片和恢复卡片。
5. 改造通知中心页面和业务配置。
6. 完成自动测试、Java 集成、Vue 浏览器验收。
7. 单独授权后执行真实飞书测试和正式配置。

## 15. 不在本次范围

- 自动解散事故群。
- 复杂多级值班表和跨时区排班。
- 自动修复、自动回滚或自动变更生产配置。
- AI 根因分析。
- 短信、电话和其他通知渠道。
- 将所有历史待压缩通知改造成事故。
- 飞书 OAuth、企业 SSO 和网页用户身份体系；该能力通过后才能开放网页写操作。
