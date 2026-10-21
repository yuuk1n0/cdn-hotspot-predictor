# 第三阶段 Java：持久任务、Python 调用与 Top50 快照

> 2026-10-21 规划入口：[整体架构](../output/20261021_架构与半天开发规划_v01/architecture.md)、[半天开发顺序](../output/20261021_架构与半天开发规划_v01/development-plan.md)。既有预测接口及表直接复用，本轮只更新文档；以下2026-10-20迁移/联调内容为历史记录，不代表本轮重跑或其他目标库已迁移。工作区另有进行中的真实算法模式兼容改动，未由本轮验证。

第③步的持久任务和异步编排原样复用，第⑤步增加获取与校验Python结果、组织最多50条并事务发布榜单。只修改Java服务；不接收或解析CSV、不读Python内部文件、不执行或重复计算模型，不将新运行写入原Fake榜单表。

## 数据库变更与本地执行记录

现有项目没有 Flyway/Liquibase 或版本化迁移 runner，仅有阶段②的 `schema.sql` / `data.sql`。本次只新增独立增量脚本：

`src/main/resources/db/migrations/003_prediction_task.sql`

该脚本只创建 `prediction_task` 一张表和对应索引，不修改或清空 `top50_prediction`，不写种子数据。必须经用户明确批准后才可对指定 MySQL 库执行；不因启动服务自动执行它。历史 `schema.sql`、`data.sql` 未改写。

2026-10-20 用户已批准，并已在 `127.0.0.1:13306/cdn_hotspot` 执行该脚本，验证唯一索引及原 32 条 Fake 数据不变。同一库不要再次执行 CREATE TABLE；其他目标库仍须先确认。验收记录见 `../output/20261020_第三环节MySQL联调验收_v01.md`。

第⑤步仅新增 `src/main/resources/db/migrations/004_prediction_run.sql`。**2026-10-20用户明确批准后，已在`127.0.0.1:13306/cdn_hotspot`执行并完成真实联调。** 它只创建`prediction_run`：runId主键、taskId唯一、外键关联已有任务，保存完整Top50 JSON快照、来源和发布时间。不ALTER既有表，不改写003、不改种子或原型数据，`prediction_task`表结构不变。该目标库不要重复执行003或004；其他库仍必须另获批准并核对既有迁移。验收见`../output/20261020_第五环节MySQL联调验收_v01.md`。

新任务查询使用快照关联，因此其他未建`prediction_run`的环境中，预测相关接口会明确503，调度器只报告存储不可用，不自动建表；独立的旧Fake查询不受影响。当前已批准本机库的迁移及快照持久化验收通过，不代表其他库已迁移。

当前 `spring.sql.init.mode=never`，避免每次重启执行旧 `data.sql` 的 DELETE 和种子重建。现存 Fake 榜单仍可按原 API 查询；空库不自动初始化。根README已同步为上述启动行为；不要重播阶段②种子。

任务表未创建或数据库不可用时，预测提交返回 `503 TASK_STORAGE_UNAVAILABLE`，不返回假 taskId、不转用内存任务。调度器只读检查并限频输出故障提示，不自动建表。

## 启动

003及004均已获批并执行后，在 backend 目录使用原有启动方式：

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

Python成功不等于Java业务成功。只有校验、Top50快照和SUCCEEDED事务提交后，Java任务才返回业务runId与predictionSource。PENDING/RUNNING/FAILED的runId均为null。

### 查询业务榜单快照

`GET /api/v1/predictions/{runId}`：只查询本地成功发布的快照，不访问Python。返回运行元数据、taskId、candidateCount、top50Count、十进制字符串top50PredictedBytes和items；可直接供第⑥步现有前端使用。不存在或未发布返回404 RUN_NOT_FOUND，不用原型数据兜底。

保留datasetId、App、截止日、模型版本、REAL/FAKE数据来源及MOCK/MODEL预测来源。预测窗口是cutoffDate后1天或连续7天，含首尾，非自然周。predictedBytes已经是窗口累计B；Java只用BigDecimal精确求Top50合计，不再乘7。未知文件类型、大小、实际流量和压缩状态均为null；不关联身份尚未确认的Fake业务行。

热点占比返回value=null、denominatorBytes=null及reasonCode；MOCK为MOCK_RESULT_AND_NO_MATCHING_TOTAL，MODEL为NO_MATCHING_TOTAL。本轮不扩展全量流量分母、算法效果或收益计算。

## 主动调用与恢复

1. 从持久任务表领取到期的 PENDING/RUNNING 任务，标记 RUNNING / VALIDATING。
2. `GET Python /api/v1/datasets/{datasetId}`，检查 READY、FILE_DAILY、来源 REAL/FAKE、B 单位、App 及该 App 的截止日期范围。
3. 先 `GET Python /api/v1/tasks/{taskId}`；仅 404 时才向 `POST Python /api/v1/predictions` 发送原 taskId 及五项业务参数，要求 202。响应丢失或 Java 重启后仍先查原任务，不随意新建 taskId。
4. Python PENDING/RUNNING 时保持 Java RUNNING / COMPUTING，按持久化 nextAttemptAt 继续轮询。单次超时、连接失败、HTTP 408/425/429/5xx 属于临时依赖故障，记录 lastDependencyError，按有限退避重试；不冒充算法失败。
5. Python FAILED 同步明确错误。POST的404仍按PYTHON_API_UNAVAILABLE重试（用于接口暂未就绪）；超过总时限为FAILED/TASK_TIMEOUT。第④步已有真实Python预测接口，无需开发替身。
6. Python SUCCEEDED后保持Java RUNNING/AGGREGATING，持久保存原python_run_id，再请求`GET /api/v1/predictions/{runId}/items?page=1&pageSize=50`。检查元数据/来源/窗口/时间/分页与完整条数、文件身份唯一、非负十进制流量、连续名次及稳定排序；错误为INVALID_RESULT，不排序修补或补足50条。
7. 仅取第一页最多50条，保存Python声明的完整候选总数；同值按精确fileKey的Unicode码点顺序核对，保留URL/无协议路径差异。未知业务属性不从原型表借用。
8. 事务锁定当前任务，再核对原总截止时间，插入独立快照并更新任务SUCCEEDED/COMPLETED；任何一步失败全部回滚。唯一约束防止同任务重复发布或同runId覆盖其他任务。任务查询与快照查询只看到已提交状态。

Java不根据模型版本前缀选择Mock，不发送mock标志，不对真实模型错误回退。所有每文件预测均由Python负责；Java只校验、选择Top50并求和。成功运行的结果GET即使Python离线仍可读取。

任务请求、幂等信息、执行状态、错误、依赖状态、重试次数、下一次处理时间与总截止时间全部落库。Java 重启后重新扫描未终结任务，沿用原 deadlineAt 和 taskId；超时或失败的任务不接收迟到结果。不重建或覆盖旧 Fake 榜单。

结果获取网络错误、5xx/超时及事务存储暂不可用时保留RUNNING/WAITING_DEPENDENCY与lastDependencyError，有界退避后重查原taskId和runId。Python已成功却返回结果404属于INVALID_RESULT，不冒充空榜单。已确认的Python runId若改变则拒绝发布。重启不重设期限；已成功快照不再调Python，失败不覆盖其他成功快照。

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

测试只在隔离H2内存/文件库创建测试fixture，不执行上述业务迁移脚本、不连接真实MySQL。覆盖既有任务/API、幂等、超时/断连/重启及新增快照校验、精确求和、事务回滚/重试和成功结果离线读取。`PredictionProcessRestartTest`使用测试专用Python契约响应；`PredictionPythonIntegrationTest`则可显式启用真实Python服务，不把契约替身当作实际算法联调。

显式运行真实Python与Java进程验收（路径替换为本机已有Python环境，勿为测试安装依赖）：

```bash
cd backend
PREDICTOR_TEST_PYTHON=/home/yukino/miniconda3/envs/cdn-predictor/bin/python mvn -o test
```

此用例在测试临时H2库载入独立fixture及原32条Fake样本，通过HTTP把真实10350.csv直接上传Python，再提交Java任务。验证短Mock与实际100秒Mock使用同一契约、Java在运行中重启、成功后关闭Python并重启Java仍可读取榜单，且原型查询结果不变。Python持久目录及端口均隔离，进程随测试结束退出；不使用默认runtime，不修改服务源码或部署。未设置PREDICTOR_TEST_PYTHON时明确跳过此慢用例。

H2测试不能代替MySQL验收。2026-10-20用户批准执行004后，另运行了真实MySQL验收：短Mock和实际100秒Mock各发布一份50条快照，候选930个，累计32741011525971 B；验证幂等、409、语义错误、暂时断连重试、Java重启、Python离线快照读取及原32条Fake数据不变。未做浏览器验收，也未实现真实算法。

本机目标库的第⑤步验收脚本为`scripts/verify_mysql_top50.py`。确认库已获批完成003/004、MySQL在13306启动，且当前Java JAR已重新打包后，从项目根目录运行：

```bash
/home/yukino/miniconda3/envs/cdn-predictor/bin/python backend/scripts/verify_mysql_top50.py
```

脚本不执行迁移、不清理数据；每次运行保留4条新验收任务（2成功、2语义失败）和2份快照。Java/Python在独立端口启动，Python数据及日志保存在`runtime/step5mysql_20261020_<批次>/`；成功结果保存在已批准的真实MySQL，服务进程在结束时自动停止。100秒计算期间通过仅修改验收Java进程的Python地址模拟真实连接拒绝，不中断Python计算；恢复正确地址后重试原任务及原截止时间。当前实际批次为`step5mysql_20261020_b44a938e`。本轮启动的MySQL也已正常关闭，表和数据保留。

历史第③步MySQL验收脚本为`scripts/verify_mysql_flow.py`。其断言针对当时Python预测接口未就绪的行为，**不作为第⑤步当前成功链路验收脚本**。历史运行方式：

```bash
conda run --no-capture-output -n cdn-predictor python backend/scripts/verify_mysql_flow.py
```

运行前须自行确认该库已经获批并完成迁移、MySQL 正在 13306 运行。脚本只启动既有 Java/Python 程序，不修改服务源码；使用真实 10350.csv，在独立端口验证 Python 导入与重启、Java 幂等/错误/重试、真实 MySQL 落库及 Java 重启。本次运行只在进程参数中使用 20 秒总时限，文件中的默认 180 秒不变。每次运行保留三条新的验收任务，不清库、不删除记录；日志保存于 runtime/step3mysql_20261020_<批次>/，Java/Python 验收进程退出时自动停止。

第⑤步代码和已批准目标库的MySQL验收完成后停止，不自动进入其他模块。004只在本次明确批准的目标库执行过一次。
