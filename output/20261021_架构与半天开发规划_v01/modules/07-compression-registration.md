# 07 压缩登记与处理状态

版本：20261021 v01。任务：T08 Java → T09 Vue。依赖[06文件](06-file-access.md)；不执行真实转码。本模块接口和表为**拟议**。

## 1. 功能目标

对应考核2.2、3.3.2。登记人工已经完成或正在处理的压缩，保存原/后文件、大小、质量、处理人、备注和来源；自动计算压缩率，供推荐过滤和效果追踪。登记完成不等于“有效压缩”。

## 2. 页面与交互

从文件详情进入登记，可标记处理中或填写完整结果标记已压缩。必须显示App、原fileKey、关联runId和REAL/TEST证据范围。原大小可从CStore查询；TEST允许人工输入并注明来源。完成登记至少填写压缩后标识/版本、压缩后大小、质量证据、处理人和备注（无备注可空串）。展示压缩率、30%门槛、质量判断及仍需观察的说明。刷新后从Java读取状态。

## 3. 数据契约

| Method + Path | 参数 | 响应 |
|---|---|---|
| POST `/api/v1/compressions` | 下例；Idempotency-Key必填 | 201 compression记录；同键同体返回已有记录（200）；同键不同体409 |
| PUT `/api/v1/compressions/{id}` | appId:string必填；body为该记录可更新字段与version:int | 200更新记录；版本冲突409 |
| GET `/api/v1/compressions` | appId:string必填；fileKey:string、evidenceSource=`REAL/TEST`、status可选；page/pageSize默认1/20，上限100 | `{page,pageSize,total,items:record[]}` |

记录字段：id/runId/appId/fileKey:string，evidenceSource:`REAL/TEST`，status:`PROCESSING/COMPRESSED`（无记录的业务状态为PENDING），originalSizeBytes/compressedSizeBytes:string/null，compressedFileKey:string/null，compressedVersion:string/null，quality:object/null，handler/remark:string，compressedAt:timestamp/null，compressionRatio:number/null，qualityPassed:boolean/null，compressionThresholdPassed:boolean/null，createdAt/updatedAt:timestamp，version:int。

```json
{
  "runId":"run_example","appId":"10350","fileKey":"example/a.mp4",
  "evidenceSource":"TEST","status":"COMPRESSED","originalSizeBytes":"100000000",
  "originalSizeSource":"MANUAL_TEST","compressedFileKey":"example/a-small.mp4",
  "compressedVersion":null,"compressedSizeBytes":"60000000",
  "quality":{"metric":"VMAF","value":92,"evidenceNote":"注明来源的压缩测试报告"},
  "handler":"测试处理人","remark":"仅用于演示，非线上处理",
  "compressedAt":"2026-10-06T02:00:00Z"
}
```

```json
{
  "id":"cmp_example","appId":"10350","fileKey":"example/a.mp4",
  "runId":"run_example","evidenceSource":"TEST","status":"COMPRESSED",
  "compressionRatio":0.4,"compressionThresholdPassed":true,"qualityPassed":true,
  "version":1,"effectiveCompressionStatus":"PENDING_OBSERVATION"
}
```

示例响应省略请求原字段。COMPRESSED时：原大小>0，后大小>0且≤原大小，后标识/版本至少一项；quality、handler、compressedAt必填。质量metric=`VMAF/SSIM/MANUAL_REVIEW/UNKNOWN`；VMAF为0～100，SSIM为0～1；MANUAL_REVIEW还需passed:boolean及evidenceNote，UNKNOWN需说明为什么未知并返回qualityPassed=null。非视频可人工复核；视频若用其他指标必须附解释，不能默认等价VMAF。

PROCESSING只需App/原身份/run/证据范围/处理人，其余可null；没有记录为PENDING，不建立一张待处理表。PUT允许PROCESSING→COMPRESSED；COMPRESSED后可补充或修正质量证据、处理人/备注，不在v1覆盖文件版本或修改已绑定观测的大小。重新压缩、多版本历史留后续，不伪装支持。

## 4. 服务职责

| 端 | 单卡范围 |
|---|---|
| Java／T08 | compression控制器、业务服务和Repository；Decimal算压缩率/质量门槛；关联run所属App；为推荐提供批量状态查询 |
| Vue／T09 | 登记表单、处理中动作、质量提示和详情关联；不自己作最终判定 |
| Python | **不修改**，不写压缩状态或收益 |
| MySQL | 拟议compression_record：id、app_id、file_key原文、file_key_hash、evidence_source、status、record_json、version、幂等键/请求摘要、created_at_ms、updated_at_ms；按App+原身份摘要+证据来源唯一，命中后仍核对原文 |

fileKey按原文精确比较，摘要只用于索引，不改身份。record_json保存大小、质量、处理人、备注、观测等小记录，避免另建多张明细表。T08只编写增量SQL；执行目标MySQL前另行说明影响范围并确认，不自动执行或改变旧表。

## 5. 最小实现方式

原大小REAL记录由Java调用CStore核对，并保存查询时间和来源；TEST可使用MANUAL_TEST。压缩后大小与质量允许用户提供，但必须保存证据说明，不能称为机器独立验证。runId只证明业务上下文，验证所属App；FAKE运行只能绑定TEST证据，不要求文件已在原始Top50快照中，便于后续完整候选补位后的登记。真实文件身份仍按模块06验证。

`compressionRatio=1−compressedSizeBytes/originalSizeBytes`；达到0.3才通过压缩率门槛。VMAF≥90或SSIM≥0.95才通过对应视频指标；未知为null。压缩率门槛和质量提示分开返回。成功记录仍待观察访问、兼容性与净收益，不能直接判有效。事务更新单行并校验version，防止观测与表单更新互相覆盖。

## 6. Mock与真实实现边界

压缩率/质量公式、持久化及状态是真实逻辑，无转码Mock。TEST记录必须有来源，且只影响TEST业务投影。MOCK预测不允许被压缩登记改成MODEL；真实人工处理也不能证明预测正确。未来真实算法不改变本模块输入输出。

## 7. 异常处理

422 APP_MISMATCH/INVALID_SIZE/MISSING_EVIDENCE；409 ALREADY_REGISTERED/VERSION_CONFLICT/INVALID_STATUS_TRANSITION；503存储或CStore依赖失败。CStore大小与输入不一致返回409 ORIGINAL_SIZE_CHANGED，要求刷新核对。后文件比原文件大提示不满足当前压缩登记条件，不伪造正压缩率。质量未知允许记录但不显示通过；REAL不能借TEST证据补齐。

## 8. 验收标准

1. TEST原100,000,000B、后60,000,000B得到0.4；VMAF92通过质量门槛，但有效性为待观察。
2. VMAF89、SSIM0.94分别不通过；质量UNKNOWN返回null；不因声明COMPRESSED自动显示有效。
3. 创建PROCESSING，再PUT完整结果到COMPRESSED；刷新读取一致，重复提交不重复创建，旧version更新返回409。
4. 相同fileKey的另一App、另一证据来源互不影响；run与App不匹配失败。
5. 原大小0、后大小负数/大于原大小、缺后版本、缺处理人或缺质量说明均明确失败。
