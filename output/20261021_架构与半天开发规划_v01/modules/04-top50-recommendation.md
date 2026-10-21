# 04 运行筛选与待压缩Top50

版本：20261021 v01。任务：T10 Java → T11 Vue。依赖既有预测快照、Python完整候选及[07登记](07-compression-registration.md)。本模块新增接口为**拟议**。

## 1. 功能目标

对应考核1.2、3.3.1。把“预测热点Top50”和“待压缩Top50”明确分开：前者保留既有不可变快照，后者从完整候选按当前业务状态筛选并补位。补齐新运行的App、日期、日周、版本、状态筛选，不把旧Fake筛选能力当作新运行已实现。

## 2. 页面与交互

先按App、预测开始日范围、日/周、版本选择已成功发布的运行，再展示该run的预测快照或业务推荐视图。处理状态默认PENDING；可切换PROCESSING/COMPRESSED/ALL作为管理查询，标题不能仍叫“待压缩”。保留旧Fake区域独立来源。

表格保留排名、文件标识、类型、大小、预测流量、分数、理由、最近实际流量、处理状态、模型；未知显示“—”。新增详情/登记按钮，调用[06文件](06-file-access.md)和[07登记](07-compression-registration.md)；登记后仅刷新业务投影，不修改预测快照。测试证据范围在页面明显显示。

## 3. 数据契约

| Method + Path | 请求参数与类型 | 返回 |
|---|---|---|
| GET `/api/v1/predictions`（拟议Java） | appId:string必填；startDate/endDate:date可选，筛选predictionStartDate；horizonDays:int 1/7、modelVersion:string可选；page:int默认1，pageSize:int 1～100默认20 | `{page,pageSize,total,items:runSummary[]}`，只含Java已发布成功运行 |
| GET `/api/v1/recommendations`（拟议Java） | runId/appId:string必填；compressionStatus=`PENDING/PROCESSING/COMPRESSED/ALL`默认PENDING；evidenceSource=`REAL/TEST`可选 | 下例，最多50条；固定选中run，不自动改“最新” |
| GET Python `/api/v1/predictions/{runId}/items?page=…&pageSize=200`（已有） | Java主动逐页读取 | 已排序的全部候选分页；每页必须校验运行关联和连续全局名次 |

runSummary包含`runId/taskId/datasetId/appId/cutoffDate/horizonDays/modelVersion/datasetSource/predictionSource/predictionStartDate/predictionEndDate/generatedAt`；字符串/日期类型沿用[03](03-prediction-task.md)。按发布时间降序、runId固定次序分页。

```json
{
  "runId":"run_example","appId":"10350","datasetId":"ds_example",
  "cutoffDate":"2026-10-06","horizonDays":7,"predictionStartDate":"2026-10-07",
  "predictionEndDate":"2026-10-13","modelVersion":"contract-demo-v1",
  "datasetSource":"REAL","predictionSource":"MOCK","evidenceSource":"TEST",
  "listKind":"RECOMMENDATION","compressionStatus":"PENDING","candidateCount":60,
  "selectedCount":1,"complete":true,
  "items":[{"rank":1,"predictionRank":2,"fileKey":"example/b.mp4","predictedBytes":"7000","score":null,"reason":"模拟结果，仅验证链路","fileType":null,"fileSizeBytes":null,"recentActualBytes":null,"compressionStatus":"PENDING","compressionId":null}]
}
```

示例假定其余59条候选均被过滤，items.length等于selectedCount。rank是本业务视图连续名次，predictionRank保留原预测名次。`listKind=RECOMMENDATION/MANAGEMENT`。若来源为FAKE或MOCK，默认TEST；REAL+MODEL默认REAL，可显式选TEST做演示。FAKE数据不允许绑定REAL业务证据。MOCK+REAL证据范围必须由用户显式选择并保留模拟标签，不能据此宣称预测可靠。

## 4. 服务职责

| 端 | 工作与单卡范围 |
|---|---|
| Java／T10 | 查询既有任务/运行生成列表；新增recommendation服务；复用压缩业务服务读状态，主动取完整候选并补位；不改原快照GET |
| Vue／T11 | 新运行选择、状态/来源筛选、表格动作、登记后刷新；不在浏览器过滤前50或重新排序预测 |
| Python | **不修改**，复用完整候选分页；不读取压缩状态 |
| MySQL | 不新增表；prediction_task/run按App筛选；compression_record按App+精确fileKey+证据来源叠加 |

## 5. 最小实现方式

1. 校验Java运行存在且App一致，保留窗口和来源。
2. 默认推荐排除PROCESSING和COMPRESSED；无记录为PENDING。已登记但未达到有效压缩仍为COMPRESSED，效果资格另由模块08解释，不自动重排成待处理。
3. 从Python第一页起扫描，批量从Java压缩服务取得这些身份的状态；过滤后不足50继续下一页，直到够50或候选耗尽。单次最多处理现有候选，不新建候选明细表。
4. Java对跨页重复、元数据改变、名次异常返回错误，不修补；成功时complete=true表示已取得足够匹配项或已耗尽全候选，不代表数据覆盖完整CDN。
5. 文件大小只叠加已有证据；未查询CStore的未知值保持null。实际流量后续可通过模块02指定日期查得，不从Fake借值。收益不在此模块重复计算。

## 6. Mock与真实实现边界

过滤和状态关联是真实逻辑；预测来源沿用运行。真实模型上线只改变候选值，不改变业务筛选。Mock推荐和收益仍须标模拟。官方模型评价使用原始预测名单，不能拿业务过滤后的名单评价模型。

## 7. 异常处理

404 RUN_NOT_FOUND；422 APP_MISMATCH；503 Python或业务存储不可用；422 INVALID_RESULT用于跨页关联/排序错误。已有Java快照即使Python离线仍可GET，但新推荐查询不能假设有完整候选：返回明确不可用，提供“查看原始预测快照”操作，不把过滤后短榜伪装为完整推荐。空匹配项返回200空items，不补文件。

## 8. 验收标准

1. 用60个可控FAKE候选，默认返回原前50；登记原第1为COMPRESSED后，原第51补入，predictionRank保留，业务rank从1连续。
2. GET原run快照前后完全一致，Top50合计不因登记改变。
3. 相同fileKey的另一App、另一证据来源记录不影响本榜。PROCESSING和COMPRESSED筛选标题为管理视图。
4. 日期筛选按预测开始日，日/周和版本只选择匹配运行；空列表不退回旧Fake。
5. 模拟第二页失败，API明确报错，不返回complete=true的部分榜；Python离线仍可读原快照。
