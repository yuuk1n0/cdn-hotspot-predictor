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

本次不实现导入状态查询、质量报告、datasetId、预测、Mock 或榜单接入。Python 当前仅有健康检查，无上传接口：**前端已完成，真实上传联调待第②步验证**。第②步需验证 Python 实际接收 multipart、幂等键和文件原始字节，并返回持久化的 importTaskId。
