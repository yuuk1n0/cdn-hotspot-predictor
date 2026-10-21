# 09 飞书每日清单与漏处理提醒

版本：20261021 v01。任务：T17 Java → T18 Vue。依赖[04推荐](04-top50-recommendation.md)、[07登记](07-compression-registration.md)；效果金额仅调用[08](08-effect-and-benefit.md)。接口及表均为**拟议**。

## 1. 功能目标

对应考核1.2、3.4及Agent逾期查询。采用飞书群机器人Webhook，发送每日清单与未处理提醒；预览、业务去重键、发送结果和有限重试可查询。不引入MQ或复杂调度系统，不承诺Webhook严格恰好一次。

## 2. 页面与交互

按App、run、通知类型、BUSINESS/DEMO用途预览。标题包含业务日期、跨度、模型、REAL/FAKE及MOCK/MODEL；DEMO历史清单必须显著说明。预览不发送。用户确认目标测试群后手动发送，展示SENT/FAILED/UNKNOWN及脱敏错误；刷新可看记录。自动发送由App配置显式开启，默认关闭。

## 3. 数据契约

| Method + Path | Java请求 | 响应 |
|---|---|---|
| GET `/api/v1/notifications/preview` | appId/runId:string，type=`DAILY/OVERDUE`，purpose=`BUSINESS/DEMO`；evidenceSource=`REAL/TEST` | `{appId,runId,type,purpose,businessDate,modelVersion,horizonDays,itemCount,items,text,canSend,reasonCodes}` |
| POST `/api/v1/notifications/send` | 下例；Idempotency-Key头；只选择已配置destinationId，不收任意Webhook URL | 202 `{notificationId,status}`，后台发送 |
| GET `/api/v1/notifications` | appId必填，runId/type可选；page/pageSize默认1/20，上限100 | 发送记录分页：ID、上下文、status、attemptCount、lastError、sentAt、nextAttemptAt |
| GET `/api/v1/overdue` | appId/runId:string必填，evidenceSource | `{appId,runId,asOf,handlingHours,items:[fileKey,compressionStatus,dueAt]}` |

```json
{
  "appId":"10350","runId":"run_example","type":"DAILY","purpose":"DEMO",
  "evidenceSource":"TEST","destinationId":"feishu-test"
}
```

```json
{
  "notificationId":"ntf_example","appId":"10350","runId":"run_example",
  "type":"DAILY","purpose":"DEMO","status":"SENT","attemptCount":1,
  "lastError":null,"sentAt":"2026-10-21T01:00:00Z","nextAttemptAt":null
}
```

状态`PENDING/SENDING/SENT/FAILED/UNKNOWN/SKIPPED`。上游Webhook的URL、签名与错误结构根据实际机器人配置映射；本轮未获得具体凭证，不伪造API响应。目的地凭证只在进程环境，数据库和响应仅有destinationId。

## 4. 服务职责

| 端 | 单卡范围 |
|---|---|
| Java／T17 | notification控制器、定时器、Webhook适配；调用同一推荐/效果服务；创建notification_delivery及app_config最小存储供T19复用 |
| Vue／T18 | 预览、发送按钮、目标/用途确认及记录面板；不定时直调Webhook |
| Python | **不修改**；不主动通知Java或飞书 |
| MySQL | notification_delivery：id、app_id、run_id、type、purpose、evidence_source、destination_id、business_key_hash唯一、idempotency_key_hash唯一、request_hash、status、payload摘要/脱敏正文、attempt_count、next_attempt_at_ms、last_error、created_at_ms、sent_at_ms；app_config：app_id主键、settings_json、version、updated_at_ms |

增量脚本仅在后续本卡编写，执行另确认。app_config不保存Webhook原值，仅保存逻辑目的地ID、开关、handlingHours。T19沿用此表，不重复建表。

## 5. 最小实现方式

- 单实例Spring定时器，每天上海09:00处理已开启App的日清单；逾期检查每小时。调度时刻和处理时限从App配置读，默认handlingHours=24。
- 日清单仅选该App、当前日预测开始、日跨度1、当前配置版本且来源REAL+MODEL的最新成功运行；没有则记录SKIPPED/NO_CURRENT_RUN，不把历史榜改称今天。周清单只在手动选择明确run时发送，不强建自动预测任务。
- 逾期范围绑定选定run；dueAt=Java快照发布时间+handlingHours，状态非COMPRESSED且当前时间超过dueAt即逾期。PROCESSING超过时限仍提醒。相同fileKey在该run只一条；本版不跨运行累计最早推荐时间，不能声称有长期全局待办台账。
- 正式BUSINESS发送要求REAL数据、MODEL预测、REAL证据范围和匹配业务日；否则只能显式DEMO发送到测试目的地。正文最多50条，若Webhook消息长度限制需要截短则展示前10及总数和系统run链接，不能假称完整正文含50条。
- 去重键为App+run+业务日+type+purpose+evidenceSource+destinationId；重复手动键和定时触发都复用同记录，SENT不重复发送。OVERDUE每业务日最多一条，后续日期可再提醒。
- 可确认的拒绝/服务错误按实际上游语义处理；仅明确可重试且能确认未成功的错误最多重试3次（含首次共4次尝试），间隔1/5/15分钟。请求超时、连接重置等无法确认是否送达时标UNKNOWN，不自动重发。重启时SENDING无明确完成证据改UNKNOWN；PENDING及已确认重试按持久时间继续。
- 预览无外部副作用。实际测试群发送必须有用户针对目标和消息的明确授权；编写文档不等于已经授权本轮发消息。

## 6. Mock与真实实现边界

Webhook和发送记录是真实实现；Mock清单必须DEMO、测试目的地，不能冒充正式每日运营。测试中的HTTP替身只验证状态机，不算飞书接入。没有真实模型时自动正式发送SKIPPED，是能力边界而非“全部通知考核已通过”。

## 7. 异常处理

错App422；无run404；未配置503；BUSINESS来源/日期不满足409 SOURCE_NOT_ELIGIBLE；重复请求复用记录。查询推荐失败不发送空成功清单。上游成功需校验业务响应，不只看HTTP200。UNKNOWN提示人工核对群消息；如需重发，先确认并显式新操作，不自动冲掉原记录。

## 8. 验收标准

1. 预览无外部请求；授权后向指定测试群发送一条DEMO消息，与API榜单一致，包含日期、跨度、版本、来源；发送记录SENT。
2. 重复点击及同一业务键定时触发不重复发。两App相同fileKey的消息与状态分开。
3. 用测试时钟跨过24小时，PENDING/PROCESSING逾期，COMPRESSED排除；不能靠修改真实系统时间测试。
4. 明确上游失败、成功业务码错误、断网超时、重启SENDING分别得到可解释状态；未知结果不自动再发。
5. 没有当日REAL+MODEL运行时自动发送SKIPPED，历史Mock只能作为标明日期的DEMO清单，不伪造“今日热点”。
