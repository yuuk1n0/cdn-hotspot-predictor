# 08 效果追踪、收益估算与有效压缩

版本：20261021 v01。任务：T12 Java → T13 Vue。依赖[07登记](07-compression-registration.md)；需要历史观测时调用[02历史](02-history-dashboard.md)。本模块接口为**拟议**。

## 1. 功能目标

对应考核2.2、3.3.3和Agent效果问答。以人工登记的可追溯观测为第一版，计算预期/事后情景估算，判断7/30天窗口的有效压缩资格。不能把体积差、Mock流量或估算金额称为实际账单降本。

## 2. 页面与交互

详情展示原/后版本、压缩率、质量、观察期与证据来源。可登记7天或30天窗口的访问次数、原文件等价请求流量、压缩版本承接比例、访问/播放状态、成本及证据说明。缺值保留未知，不默认承接100%或成本0。

统计区按App、证据范围、观察窗口结束日筛选，展示已登记/待观察/有效/未通过/未知数量和“可计算记录的估算合计”。趋势按**观察窗口结束日**分组，不把七天估算画成每日真实节省。预测估算另显示run及目标窗口，不能与事后窗口合并。

## 3. 数据契约

| Method + Path | 请求 | 输出 |
|---|---|---|
| POST `/api/v1/compressions/{id}/observations` | appId:string，Idempotency-Key头，以下观测JSON | 201观测与有效性；重复同键同体200，冲突409 |
| GET `/api/v1/effects` | appId:string必填；compressionId:string、evidenceSource=`REAL/TEST`、startDate/endDate（观察结束日）、windowDays=7/30可选；page/pageSize默认1/20，上限100 | items、summary、窗口结束日统计；不跨App合并 |
| POST `/api/v1/benefit-estimates` | appId/runId/fileKey必填；routedShare:number/null，transcodingCostYuan/storageCostYuan:string/null；evidenceSource | 原运行元数据、压缩证据、estimatedSavedBytes/estimatedNetBenefitYuan:string/null，missingFields:string[]，estimateBasis |

预测B必须由Java根据run/file取得，调用者不能传一个任意预测值覆盖来源。压缩率取已登记大小；未登记时返回缺失，不在此创建影子记录。CDN单价固定题设`"0.02"`元/十进制GB。

```json
{
  "appId":"10350","windowDays":7,"startDate":"2026-10-07","endDate":"2026-10-13",
  "baselineTrafficBytes":"1000000000000","routedShare":0.8,"actualAccessCount":10000,
  "accessible":true,"transcodingCostYuan":"1.00","storageCostYuan":"0.40",
  "evidenceSource":"TEST","evidenceNote":"有来源测试：baseline为窗口全部请求按原大小折算的流量，不是压缩后流量",
  "qualityEvidenceNote":"同一压缩版本的VMAF测试报告"
}
```

baselineTrafficBytes为非负整数字节字符串/null，actualAccessCount为非负int/null，routedShare为0～1有限number/null，accessible为boolean/null，成本为非负十进制字符串/null。证据范围必须等于压缩记录，不允许调用者借此改变来源。

```json
{
  "compressionId":"cmp_example","appId":"10350","windowDays":7,
  "startDate":"2026-10-07","endDate":"2026-10-13","evidenceSource":"TEST",
  "compressionRatio":0.4,"estimatedSavedBytes":"320000000000",
  "cdnUnitPriceYuanPerGB":"0.02","estimatedCdnSavingYuan":"6.40",
  "estimatedNetBenefitYuan":"5.00","estimateBasis":"POST_OBSERVATION_ESTIMATE",
  "effectiveCompressionStatus":"PASS","reasonCodes":[],"isRealizedBillSaving":false
}
```

这是手算测试场景，不是线上效果。状态：`PENDING_OBSERVATION/UNKNOWN/PASS/FAIL`；estimateBasis=`MOCK_FORECAST_ESTIMATE/MODEL_FORECAST_ESTIMATE/POST_OBSERVATION_ESTIMATE`。响应还要保留证据note、观测登记时间和原大小来源。

## 4. 服务职责

| 端 | 单卡范围 |
|---|---|
| Java／T12 | 在压缩模块增加观测/收益服务及接口；BigDecimal计算；统一供榜单、通知和Agent使用 |
| Vue／T13 | 证据表单、缺参提示、窗口状态、统计和明确标签；不重复计算收益 |
| Python | **不开发收益**。可经模块02提供已有观测，人工确认其口径后使用；不把标准化流量自动认证为baseline |
| MySQL | 观测作为compression_record.record_json中的小数组，按7/30各保存一份完整窗口证据；事务锁定记录更新，不另建明细表；幂等信息一同保存 |

## 5. 最小实现方式

唯一公式：

```text
压缩率 = 1 − 压缩后大小 / 原大小
预计节省B = 同窗口原等价流量B × 压缩版本承接比例 × 压缩率
估算CDN节省元 = 预计节省B / 1000000000 × 0.02
估算净收益元 = 估算CDN节省元 − 转码成本元 − 同窗口新增存储成本元
```

预测估算用运行的窗口累计预测B；事后估算用观测baselineTrafficBytes。若只有压缩后B且无可靠版本映射/承接口径，不能代入上述原等价流量。净收益可以负；金额最终展示保留2位，内部精确计算，判定净收益>0依据未展示舍入前值。汇总按唯一记录+窗口计数，同一文件多运行的预测不能重复加进事后合计。

观察从compressedAt在上海日期的**下一自然日**开始，7/30天含首尾，结束日次日00:00上海时间才成熟。示例对应压缩发生于2026-10-06。每种窗口只登记一份最终证据，重复不同内容409；修订及多轮压缩暂不实现。未成熟窗口由GET效果查询显示待观察，POST最终证据返回409 WINDOW_NOT_MATURE；不提前填写未来实测或伪造时钟。

PASS需窗口成熟、实际访问>0、压缩版本承接比例>0、压缩率≥30%、质量通过、可正常访问/播放、净收益>0，且证据齐全。任何明确失败条件为FAIL；没有明确失败但证据不足为UNKNOWN；未成熟优先PENDING_OBSERVATION。TEST可计算PASS，但只能计入测试有效数量，绝不能计入真实有效压缩。

## 6. Mock与真实实现边界

公式和判断真实实现；预测来自MOCK时estimateBasis必须MOCK_FORECAST_ESTIMATE。预测情景不得把有效压缩状态改为PASS。真实人工观测仍是声明证据，保留来源，不冒充自动测量或严格反事实账单实验。价格题设固定，不是实时市场价。未来算法只改变预测B，不改公式或窗口口径。

## 7. 异常处理

错App/来源/窗口422；记录不存在404；PROCESSING不能登记完成后效果409；窗口未成熟或观测内容冲突409；存储失败503。缺流量、承接、成本或质量时输出null、missingFields及UNKNOWN，不能补0。输入负成本或比例越界拒绝。数据日期不足以覆盖7天时不能把6天标作完整窗口。

## 8. 验收标准

1. 用模块07测试记录和上例观测，在成熟时钟下得节省320,000,000,000B、6.40元、净5.00元；真实账单标志始终false。
2. 去掉storageCostYuan，金额为null，显示缺参；填0必须是用户显式声明，来源仍可见。
3. 修改访问数0、质量未通过、净收益≤0分别为FAIL；证据未知为UNKNOWN；未来窗口为PENDING_OBSERVATION。
4. MOCK预测的估算只显示模拟，不改变实际有效状态；TEST记录不计入REAL汇总。
5. 两App及两运行查询无混合；同一观测重复提交不重复统计；按窗口结束日趋势不伪装日节省。
