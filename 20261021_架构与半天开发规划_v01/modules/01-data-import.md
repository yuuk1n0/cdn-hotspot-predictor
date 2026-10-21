# 01 数据导入与数据集选择

版本：20261021 v01。任务：T01（仅Vue）。公共约定见[架构](../architecture.md)，顺序见[计划](../development-plan.md)。以下示例编号及数值为契约示例，不是10350.csv的实测报告。

## 1. 功能目标

对应考核1.2、2.1、3.1。把现有“上传已接收”补成“可查导入状态、质量报告、选择READY数据集”。Python真实导入已实现，不重写；Java不接收CSV。

## 2. 页面与交互

- 沿用上传面板及映射、来源、单位确认；收到202后每2秒查询原importTaskId，不能立即显示导入完成。
- 成功后读取元数据和质量报告，展示行数、日期范围、App列表、编码、缺日、单位来源、身份及完整性警告。
- 加载已有READY数据集列表；先选择数据集，再从其appIds选择业务。多App不自动混合；单App可默认选择。
- 发出`dataset-selected`事件：`{datasetId, appId, datasetSource, dateRange, appDateRanges}`，由App.vue保存为后续表单上下文。T01不提交预测。
- 刷新恢复原导入编号后重新GET；列表选择可以重新获取。状态查询失败暂停并允许重查原编号，不重传文件。自动查询最多5分钟后只暂停页面等待。
- FAILED展示错误及可用质量报告；“选择另一份文件”沿用现有重置。不得用旧READY数据集冒充本次失败的输出。

## 3. 数据契约

本模块全部Python接口**已存在**。浏览器将下列`/api/v1`替换成`/data-api`。

| Method + Path | 请求与类型 | 响应关键字段与类型 |
|---|---|---|
| POST `/api/v1/imports` | multipart：file文件；mapping JSON字符串；datasetType=`FILE_DAILY`；datasetSource=`REAL/FAKE`；sourceUnit=`B`；appId可选字符串。头Idempotency-Key必填 | 202：importTaskId:string，status:string |
| GET `/api/v1/imports/{importTaskId}` | 路径string | importTaskId:string，status，datasetId:string/null，error:object/null，quality:object/null，createdAt/updatedAt:string |
| GET `/api/v1/datasets?page=1&pageSize=20` | page:int≥1，pageSize:int 1～200 | page/pageSize/total:int，items:metadata[]，仅READY |
| GET `/api/v1/datasets/{datasetId}` | 路径string | datasetId/status/type/source/unit，appIds:string[]，appDateRanges:object，rowCount:int，sha256/parserVersion/identityMappingVersion:string，completeness:string |
| GET `/api/v1/datasets/{datasetId}/quality` | 路径string | totalRows/validRows/duplicateRows/conflictRows/invalidRows:int，encoding:string，dateRange/appDateRanges，dailyRows/missingDates/warnings等 |

状态：`PENDING/RUNNING/SUCCEEDED/FAILED`；数据集`READY`。mapping示例：

```json
{"fileKey":"文件标识","date":"日期","trafficBytes":"当日流量（B）","appId":"AppID"}
```

```json
{
  "importTaskId":"imp_example",
  "status":"SUCCEEDED",
  "datasetId":"ds_example",
  "error":null,
  "quality":null,
  "createdAt":"2026-10-21T01:00:00Z",
  "updatedAt":"2026-10-21T01:00:01Z"
}
```

成功时quality可以为null，必须另GET质量端点，不能误判为空报告。元数据及报告字段以现有Python实现为准，前端不自行生成统计。

```json
{
  "datasetId":"ds_example","status":"READY","datasetType":"FILE_DAILY",
  "datasetSource":"REAL","sourceUnit":"B","appIds":["10350"],
  "appDateRanges":{"10350":{"start":"2026-10-01","end":"2026-10-13"}},
  "dateRange":{"start":"2026-10-01","end":"2026-10-13"},
  "rowCount":60,"completeness":"UNKNOWN","unitConfirmationSource":"UPLOAD_DECLARATION"
}
```

## 4. 服务职责

| 端 | 本次要求 |
|---|---|
| Vue | 改CsvImportPanel.vue、imports.js，App.vue只接选择事件；增加GET封装、轮询、报告和列表；相关测试限定本模块 |
| Java | **不修改**，不加文件转发API |
| Python | **不修改**；复用ImportStore及已有API。保持5MiB、20,000行、Decimal、不可变数据集和原子发布 |
| MySQL | **不修改**；数据集不复制入MySQL |

## 5. 最小实现方式

T01先补请求封装及状态控制，再接现有面板。取消组件时停止GET；切换任务/数据集后忽略旧响应。报告用表格和警告列表，不新加图表库。现有幂等行为保留：文件/映射不变的上传重试沿用原键，修改后新键。缺App列才使用默认appId，不从文件名推断。数据选择事件只传元数据，不传CSV内容。

## 6. Mock与真实实现边界

导入、解析和持久化是真实逻辑，禁止前端固定成功回执。REAL/FAKE由用户声明，不等于完整性认证。READY与`completeness=UNKNOWN`可同时成立；科学计数法保留原精度限制。10066的单位和大文件问题不能由此面板静默推断或截断。后续模型不改变这些接口。

## 7. 异常处理

| 场景 | 兜底 |
|---|---|
| 413文件超限/行数超限 | 展示明确上限；不截取导入、不自动调高配置 |
| INVALID_CSV、UNIT_UNCONFIRMED、DUPLICATE_CONFLICT、APP_MISMATCH | 展示后端码及报告；无可用datasetId |
| GET断网/503 | 暂停查询，保留importTaskId；重查GET，不重新POST |
| 404/损坏响应 | 显示查询失败，不转成功、不默认READY |
| 刷新或存储不可用 | 有编号重查；localStorage不可用仍可当前页操作，不编造恢复状态 |

## 8. 验收标准

1. 上传仓库10350.csv，确认浏览器POST目标是`/data-api/imports`，Java无文件请求。
2. 等待成功，页面从GET取得真实datasetId、质量报告、REAL来源及缺日/完整性警告；报告数值与API一致。
3. 刷新页面重新GET任务，仍可选择同一READY数据集和其App；没有预测POST。
4. 上传一份明确为FAKE的无效小CSV，失败时无可选的新数据集；旧数据集如保留必须标为旧选择。
5. 暂停Python或模拟GET失败，页面保留原编号，可重查；未把网络错误写成后台FAILED。
6. 前端相关测试和构建通过，原有Fake筛选榜和任务查询不退化。
