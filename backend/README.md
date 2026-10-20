# 第三阶段第③步：Java 预测请求、持久任务与 Python 调用

本环节只修改 Java 服务。复用 Spring JDBC、现有 MySQL 和现有 Top50 原型，新增预测业务任务与异步编排；不接收 CSV、不读 Python 文件、不实现 Mock、不读取或聚合预测候选。

## 数据库变更：尚未执行

现有项目没有 Flyway/Liquibase 或版本化迁移 runner，仅有阶段②的 `schema.sql` / `data.sql`。本次只新增独立增量脚本：

`src/main/resources/db/migrations/003_prediction_task.sql`

该脚本只创建 `prediction_task` 一张表和对应索引，不修改或清空 `top50_prediction`，不写种子数据。必须经用户明确批准后才可对指定 MySQL 库执行；不因启动服务自动执行它。历史 `schema.sql`、`data.sql` 未改写。

当前 `spring.sql.init.mode=never`，避免每次重启执行旧 `data.sql` 的 DELETE 和种子重建。现存 Fake 榜单仍可按原 API 查询；空库不自动初始化。本文件中的启动行为取代根 README 的阶段②自动重建种子说明。

任务表未创建或数据库不可用时，预测提交返回 `503 TASK_STORAGE_UNAVAILABLE`，不返回假 taskId、不转用内存任务。调度器只读检查并限频输出故障提示，不自动建表。

## 启动

数据库变更获批并执行后，在 backend 目录使用原有启动方式：

```bash
MYSQL_DATABASE=cdn_hotspot MYSQL_USER=cdn MYSQL_PASSWORD=cdn_dev MYSQL_PORT=13306 \
  mvn -o spring-boot:run
```

默认主动访问 Python `http://127.0.0.1:8000`，可用 `PYTHON_BASE_URL` 修改。Java 不通过 Vite 代理访问 Python。保持单实例、单调度 worker；本阶段不支持多个 Java 实例争抢同一任务表。

## 业务接口

### 提交预测

`POST /api/v1/predictions`，`Content-Type: application/json`，必填请求头 `Idempotency-Key`（1–200 字符）。

```json
{
  "datasetId": "使用第②步成功导入的真实datasetId",
  "appId": "10350",
  "cutoffDate": "2026-10-06",
  "horizonDays": 7,
  "modelVersion": "contract-demo-v1"
}
```

先校验字段格式，再持久化 PENDING 任务并立即返回 `202 {"taskId":"task_…","status":"PENDING"}`，请求线程不等待 Python 计算。ID 必须是字符串；horizonDays 必须是整数 1 或 7，表示截止日之后的下一天或连续 7 天，不是自然周。

相同幂等键和相同五项业务参数返回已有任务，响应仍为 202，status 可为其当前状态；同键不同参数返回 409。数据库唯一索引约束幂等键的 SHA256，原始幂等键不写日志或任务表。请求指纹对业务参数计算，不依赖 JSON 输入字段顺序。

### 查询 Java 任务

`GET /api/v1/tasks/{taskId}`。响应包括 taskId、五项原始参数、status、stage、datasetSource、error、lastDependencyError、attemptCount、createdAt、updatedAt、deadlineAt、nextAttemptAt。时间为 UTC ISO 8601；表内时刻保存为 UTC epoch 毫秒，避免数据库会话时区改变时限。

结构错误直接返回 422，未知 taskId 返回 404；数据集语义错误由后台持久化为 FAILED，可从任务查询获知。

第③步的 runId 和 predictionSource 始终为 null。Python 接收成功不等于 Java 业务完成；本环节不提供 `GET /predictions/{runId}`，仍沿用旧 `/api/v1/top50` 查询 Fake 原型。

## 主动调用与恢复

1. 从持久任务表领取到期的 PENDING/RUNNING 任务，标记 RUNNING / VALIDATING。
2. `GET Python /api/v1/datasets/{datasetId}`，检查 READY、FILE_DAILY、来源 REAL/FAKE、B 单位、App 及该 App 的截止日期范围。
3. 先 `GET Python /api/v1/tasks/{taskId}`；仅 404 时才向 `POST Python /api/v1/predictions` 发送原 taskId 及五项业务参数，要求 202。响应丢失或 Java 重启后仍先查原任务，不随意新建 taskId。
4. Python PENDING/RUNNING 时保持 Java RUNNING / COMPUTING，按持久化 nextAttemptAt 继续轮询。单次超时、连接失败、HTTP 408/425/429/5xx 属于临时依赖故障，记录 lastDependencyError，按有限退避重试；不冒充算法失败。
5. Python FAILED 同步明确错误。当前 Python 预测接口尚未实现，POST 的 404 显示为 PYTHON_API_UNAVAILABLE，并在总时限内重试；超过总时限转为 FAILED / TASK_TIMEOUT。
6. Python SUCCEEDED 时仅验证任务级 runId 引用。本环节因第⑤步尚未实现，明确以 `AGGREGATION_NOT_IMPLEMENTED` 结束 Java 业务任务，内部保留 python_run_id，不暴露可查询的业务 runId、不假报成功或永久保持 RUNNING。第⑤步再替换该分支为获取、校验并原子发布 Top50。

Java 不根据模型版本前缀选择 Mock，不发送 mock 标志，不对真实模型错误回退。所有预测执行由 Python 负责。当前没有预测流量处理或 Top50 快照写入。

任务请求、幂等信息、执行状态、错误、依赖状态、重试次数、下一次处理时间与总截止时间全部落库。Java 重启后重新扫描未终结任务，沿用原 deadlineAt 和 taskId；超时或失败的任务不接收迟到结果。不重建或覆盖旧 Fake 榜单。

## 可配置时限

| 环境变量 | 默认值 |
| --- | --- |
| PYTHON_BASE_URL | http://127.0.0.1:8000 |
| PREDICTION_CONNECT_TIMEOUT | 3s |
| PREDICTION_READ_TIMEOUT | 5s |
| PREDICTION_POLL_INTERVAL | 2s |
| PREDICTION_TASK_TIMEOUT | 180s |
| PREDICTION_MAX_RETRY_DELAY | 10s |
| PREDICTION_WORKER_TICK_MS | 500 |
| PREDICTION_WORKER_ENABLED | true |

关闭 worker 只用于维护或测试：任务仍可接收和查询，但不会处理，重新启用后恢复。总时限从创建任务起计算，包含排队和依赖重试，不在重启或重试时重置。

## 验证与已知边界

运行 `mvn -o test`。H2 仅在 test scope 声明，复用当前 Maven 缓存；本轮未安装或下载依赖。

测试只在隔离 H2 内存/文件库执行独立测试表 fixture，不执行上述业务迁移脚本，不连接真实 MySQL。覆盖业务 API、并发幂等、校验、Python HTTP 请求格式、超时/404/5xx、丢失响应、文件库重开恢复，以及完整 Spring Boot 真实 HTTP 调用。`PredictionProcessRestartTest` 还会启动并重启真实 Java 进程，验证自动调度、原 taskId、幂等键与截止时间恢复且不重复提交。Python 预测响应均为显式测试专用契约替身，不是生产 Mock 算法。

100 秒计算兼容性使用可控时钟推进验证，不宣称已运行第④步的真实 100 秒延迟 Mock。真实 MySQL 迁移及 Java→Python 预测成功联调仍分别等待用户批准和第④步实现。

第③步完成后停止，未经用户明确指示不进入第④步。
