# CSV 上传（第三阶段第①步）

在 `frontend/` 运行 `npm run dev`，打开 `http://localhost:5173`。使用现有看板中的“上传流量观测文件”面板：选择不超过 5 MiB 的非空 `.csv`，确认数据来源、字节单位 B 和实际列名后，点击“上传 CSV”。默认映射对应仓库 `datasets/10350.csv` 的实际表头；不从文件名推断 App ID。其他文件按实际表头修改，无 App 列时清空该列映射并填写默认 App ID。

## 请求契约

浏览器 `POST /data-api/imports`，沿用 `vite.config.js` 的既有代理，目标为 Python `http://127.0.0.1:8000/api/v1/imports`。请求不经过 Java；生产环境需保持相同路径映射，本次未修改部署配置。

请求头包含 `Idempotency-Key`。FormData 交由浏览器自动设置 multipart/form-data 和 boundary，前端不手写 Content-Type。

| multipart 字段 | 提交值 |
| --- | --- |
| file | 用户选择的原始文件，不解析、不改写 |
| mapping | JSON 字符串，默认 `{"fileKey":"文件标识","date":"日期","trafficBytes":"当日流量（B）","appId":"AppID"}` |
| datasetType | `FILE_DAILY` |
| datasetSource | 用户选择的 `REAL` 或 `FAKE` |
| sourceUnit | 用户明确确认的 `B` |
| appId | 可选；无 App 列时必填，作为默认值 |

预期响应 `HTTP 202`：

```json
{"importTaskId":"imp_example","status":"PENDING"}
```

前端仅显示“上传已接收”，不将 202 解释为导入完成。保存真实返回的字符串 importTaskId；浏览器存储可用时写入 localStorage 的 `cdn.csv-import.task`，刷新仅恢复接收记录，不查询或推断后台任务状态。“选择另一份文件”清除此记录并开始独立提交。

同一次请求失败后，配置与文件不变时重试沿用原 multipart 内容和幂等键；修改配置或重新选择文件会开始新的提交。幂等是否在服务端生效需第②步实现后验证。上传正在提交或已经接收时禁止重复提交。

错误响应兼容 `{"error":{"code":"...","message":"..."}}` 和现有原型的 `{"message":"..."}`；服务断开、HTTP 404/502 等均显示失败，缺少合法 importTaskId 或返回非 202 成功状态也不会显示已接收。

## 验证及边界

运行 `npm test` 和 `npm run build`。测试中的 HTTP 响应只用于前端单元测试，生产页面没有成功响应替身。

第①步前端范围不包含导入状态查询、质量报告、datasetId、预测或 Mock。上述内容记录上传面板的独立职责；Python 第②步的实现和实际验证以 `predictor/README.md` 为准。

# 任务查询与榜单展示（第三阶段第⑥步）

## 操作

在上传面板下方使用“预测任务与本次榜单”。选择 taskId，粘贴 **Java 已创建的预测任务编号**，点击“查询”。PENDING/RUNNING 每 2 秒查询一次；只有 Java 返回 SUCCEEDED 和 runId 后才读取榜单。也可以选择 runId 查询已发布快照。

查询时禁用重复提交；“暂停查询”只停止浏览器查询，不取消后台任务。“重新查询原编号”只执行 GET，不创建新任务。自动查询最多持续 5 分钟，超时只暂停，不将 Java 任务改成失败。网络错误停止当前查询，允许重试原编号；FAILED 展示 Java 返回的错误码和原因。最近查询类型及编号保存在 `cdn.prediction.lookup`，刷新后重新 GET；不把缓存的任务状态当成当前状态。支持 `/?taskId=...#prediction` 或 `/?runId=...#prediction`，URL 优先于本地记录。

本轮不增加预测提交表单。taskId 必须来自既有 Java 预测提交接口，不能使用 Python 的 importTaskId。

## 接口与展示契约

| 浏览器 GET | 既有 Vite 代理目标 | 用途 |
| --- | --- | --- |
| `/api/tasks/{taskId}` | Java `/api/v1/tasks/{taskId}` | 业务任务状态、阶段、数据集和参数、错误 |
| `/api/predictions/{runId}` | Java `/api/v1/predictions/{runId}` | Java 已发布的 Top50 快照 |

沿用现有 `/api` 代理，没有新增路由别名、Python 查询或 POST。结果使用 `materials/方案v1.md` §6.5 的未来 Java 契约：`runId`、`datasetId`、`appId`、`cutoffDate`、`horizonDays`、`predictionStartDate`、`predictionEndDate`、`modelVersion`、`datasetSource`、`predictionSource`、`candidateCount`、`top50Count`、`top50PredictedBytes`、`items`。条目包含 `rank`、`fileKey`、十进制字符串 `predictedBytes`；文件大小、实际流量、类型、压缩状态、分数及理由可未知。

前端校验任务/结果关联、窗口和来源；不修补错误响应，不排序、聚合或补齐候选。精确 B 字符串原样显示，额外保留原型的近似友好单位展示。周流量已经是连续 7 天累计，不乘 7。未知值显示“—”或“未知”；热点流量占比显示“不可计算”和缺少匹配分母的原因；MOCK 明确标为模拟计算。原有 Fake 看板、筛选及分页独立保留，新查询失败不从 Fake 看板取数据。保留上一份有效榜单时显式标注它不属于本次查询。

## 当前可验证范围

运行 `npm test`、`node --test --test-isolation=none`、`npm run build`。测试覆盖请求路径、轮询/终态、断连/404/503、原编号重查、刷新恢复、迟到响应隔离、模拟 100 秒后端等待、契约校验、精确字节字符串、未知值、真实 Vue 组件的服务端渲染及旧 Fake 表格兼容。

**当前第④、⑤步尚未实现：Java 尚无 `GET /api/v1/predictions/{runId}`，也不会发布成功榜单。第⑥步前端代码已实现，真实成功榜单联调待第④、⑤步。** 测试 fixture 仅存在于测试文件；页面没有假成功响应。模拟 100 秒使用测试时钟，不是实际等待 100 秒的跨服务验收；未执行真实浏览器交互或完整链路验收。
