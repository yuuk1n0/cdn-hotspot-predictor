# CSV 上传（第三阶段第①步）

业务总览可查询 READY 数据集的历史观测；缺日显示为空，显式 0B 保留，精确值以接口返回的字节数为准。

> 2026-10-21 UI 重构：当前默认首页为 `/overview`，提供业务总览、热点预测、模型回测、压缩降本、智能 Agent 五个一级页面。根目录两张 PNG 是本轮视觉参考。已有接口功能在 `/prediction` 的“接口数据 · 已有功能”中；其下保留 CSV 导入、READY 选择、预测提交/查询和原 FAKE 筛选分页。下文早期阶段记录按其日期阅读，不代表当前页面仍缺这些入口。

本轮新增 Vue Router 4.6.3；没有 UI 框架或图表库，图表使用 SVG。`src/ui/mock-data.js` 只提供 UI Mock，默认五页可在业务服务未启动时预览，不将其作为接口失败的替代数据。页面静态数字不代表预测准确率、真实压缩记录或实际收益。

```bash
cd /path/to/cdn-hotspot-predictor/frontend
export PATH=/home/yukino/.nvm/versions/node/v24.14.0/bin:$PATH
npm run dev -- --strictPort
```

打开 `http://localhost:5173`。本轮独立 UI 验收使用 `http://localhost:5175`，没有启动后端进行联调。使用已有接口功能时仍需原 Java/Python/MySQL 环境及其 `localhost:5173` 来源配置；5175 仅用于本轮静态 UI 预览。

路由使用 HTML5 history；部署时应将页面路径回退到 `index.html`。原 `/?taskId=...`、`/?runId=...#prediction`、`/#top50` 会保留参数跳转到预测页相应区域。

文件详情不提供外部文件预览或下载；压缩登记只接受人工 `TEST` 证据。

运行状态与验收结论以当前测试和实际接口为准。

在 `frontend/` 运行 `npm run dev`，打开 `http://localhost:5173`。使用“上传流量观测文件”面板选择不超过 200 MiB（209,715,200 B）的非空 CSV，确认数据来源、字节单位 B 和实际列名后上传。不从文件名推断 App ID；无 App 列时清空该列映射并填写默认 App ID。超过 200 MiB 时前端和 Python 服务端都会拒绝。

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

同一次请求失败后，配置与文件不变时重试沿用原 multipart 内容和幂等键；修改配置或重新选择文件会开始新的提交。Python第②步已实现持久幂等；浏览器完整流程仍须按T01/T02实际验收。上传正在提交或已经接收时禁止重复提交。

错误响应兼容 `{"error":{"code":"...","message":"..."}}` 和现有原型的 `{"message":"..."}`；服务断开、HTTP 404/502 等均显示失败，缺少合法 importTaskId 或返回非 202 成功状态也不会显示已接收。

## 验证及边界

运行 `npm test` 和 `npm run build`。测试中的 HTTP 响应只用于前端单元测试，生产页面没有成功响应替身。

第①步前端范围不包含导入状态查询、质量报告、datasetId、预测或 Mock。上述内容记录上传面板的独立职责；Python 第②步的实现和实际验证以 `predictor/README.md` 为准。

# 任务查询与榜单展示（第三阶段第⑥步）

## 操作

在上传面板下方使用“预测任务与本次榜单”。选择 taskId，粘贴 **Java 已创建的预测任务编号**，点击“查询”。PENDING/RUNNING 每 2 秒查询一次；只有 Java 返回 SUCCEEDED 和 runId 后才读取榜单。也可以选择 runId 查询已发布快照。

查询时禁用重复提交；“暂停查询”只停止浏览器查询，不取消后台任务。“重新查询原编号”只执行 GET，不创建新任务。自动查询最多持续 5 分钟，超时只暂停，不将 Java 任务改成失败。网络错误停止当前查询，允许重试原编号；FAILED 展示 Java 返回的错误码和原因。最近查询类型及编号保存在 `cdn.prediction.lookup`，刷新后重新 GET；不把缓存的任务状态当成当前状态。支持 `/?taskId=...#prediction` 或 `/?runId=...#prediction`，URL 优先于本地记录。

当前尚无预测提交表单，后续由T02单独补齐。taskId必须来自既有Java预测提交接口，不能使用Python的importTaskId。

## 接口与展示契约

| 浏览器 GET | 既有 Vite 代理目标 | 用途 |
| --- | --- | --- |
| `/api/tasks/{taskId}` | Java `/api/v1/tasks/{taskId}` | 业务任务状态、阶段、数据集和参数、错误 |
| `/api/predictions/{runId}` | Java `/api/v1/predictions/{runId}` | Java 已发布的 Top50 快照 |

沿用现有 `/api` 代理，没有新增路由别名、Python 查询或 POST。结果使用已实现的Java运行快照契约（对应 `materials/方案v1.md` §6.5）：`runId`、`datasetId`、`appId`、`cutoffDate`、`horizonDays`、`predictionStartDate`、`predictionEndDate`、`modelVersion`、`datasetSource`、`predictionSource`、`candidateCount`、`top50Count`、`top50PredictedBytes`、`items`。条目包含 `rank`、`fileKey`、十进制字符串 `predictedBytes`；文件大小、实际流量、类型、压缩状态、分数及理由可未知。

前端校验任务/结果关联、窗口和来源；不修补错误响应，不排序、聚合或补齐候选。精确 B 字符串原样显示，额外保留原型的近似友好单位展示。周流量已经是连续 7 天累计，不乘 7。未知值显示“—”或“未知”；热点流量占比显示“不可计算”和缺少匹配分母的原因；MOCK 明确标为模拟计算。原有 Fake 看板、筛选及分页独立保留，新查询失败不从 Fake 看板取数据。保留上一份有效榜单时显式标注它不属于本次查询。

## 当前可验证范围

运行 `npm test`、`node --test --test-isolation=none`、`npm run build`。测试覆盖请求路径、轮询/终态、断连/404/503、原编号重查、刷新恢复、迟到响应隔离、模拟 100 秒后端等待、契约校验、精确字节字符串、未知值、真实 Vue 组件的服务端渲染及旧 Fake 表格兼容。

**状态更正：第④、⑤步已实现，Java已有 `GET /api/v1/predictions/{runId}` 并可发布成功快照。当前仍缺网页导入监控、数据集选择和预测提交；完整浏览器成功链路待T01/T02验收。** 测试 fixture 仅存在于测试文件；页面没有假成功响应。模拟 100 秒使用测试时钟，不是实际等待 100 秒的跨服务验收；未执行真实浏览器交互或完整链路验收。
