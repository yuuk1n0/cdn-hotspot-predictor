# Python：CSV 导入、异步 Mock 与真实 v1 预测

> 2026-10-21 状态更正：Java获取结果与Top50快照已经实现，不能再按历史第④步停止点认定Java缺聚合。网页导入监控和预测提交仍待补齐，见[半天计划](../output/20261021_架构与半天开发规划_v01/development-plan.md)。本套规划默认Mock；工作区存在另行推进的真实算法适配，见[其独立计划](../output/20261021_真实算法集成实施计划_v01.md)，其测试/上线结论不由本次文档任务认领。

保留 CSV 导入、持久化异步任务和完整候选结果，新增 `hotspot-simple-v1` 真实算法。默认仍为 Mock，真实模式需同时设置下面两个环境变量。本服务不回调 Java，Java 继续校验并保存业务 Top50。CStore、压缩、收益、在线回测指标和完整 Web 预测提交不在本次集成范围。

## 启动与验证

使用现有 `cdn-predictor` 环境，无需本环节安装依赖：

```bash
cd predictor
conda run --no-capture-output -n cdn-predictor python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

默认持久目录是项目的 `runtime/predictor/`，与进程工作目录无关。可通过 `PREDICTOR_DATA_DIR` 指定持久卷绝对路径。本阶段使用单实例、单 worker，勿对同一目录同时启动多个服务实例或使用 `--workers > 1`。

默认文件上限 5 MiB（`IMPORT_MAX_BYTES=5242880`），数据行上限 20,000（`IMPORT_MAX_ROWS=20000`），超过上限会明确失败，不静默截断。已安装的 `python-multipart=0.0.32` 已补入 `environment.yml`；本次未执行环境安装或更新。

从项目根目录发送真实文件：

```bash
curl -i http://127.0.0.1:8000/api/v1/imports \
  -H 'Idempotency-Key: step2-10350-v1' \
  -F 'file=@datasets/10350.csv' \
  -F 'mapping={"fileKey":"文件标识","date":"日期","trafficBytes":"当日流量（B）","appId":"AppID"}' \
  -F 'datasetType=FILE_DAILY' \
  -F 'datasetSource=REAL' \
  -F 'sourceUnit=B'
```

同一次提交重试使用原幂等键；修改文件或配置应使用新键。相同键、相同内容返回同一导入任务，内容或配置不同返回 409。App 从实际行读取，不由文件名决定；可选 `appId` 用于默认值或一致性检查，无 App 列时必须提供默认值。

## 接口

| 接口 | 返回 |
| --- | --- |
| `POST /api/v1/imports` | `202 {"importTaskId":"imp_…","status":"PENDING"}`，原文件及任务已落盘；重复提交可返回现有状态 |
| `GET /api/v1/imports/{importTaskId}` | PENDING / RUNNING / SUCCEEDED / FAILED；仅成功时 datasetId 非 null；失败带 error 和可用的内嵌 quality 报告 |
| `GET /api/v1/datasets/{datasetId}` | READY 元数据，包括来源、类型、App、日期范围、映射、单位、SHA256 与解析版本 |
| `GET /api/v1/datasets/{datasetId}/quality` | 数据质量报告 |
| `GET /api/v1/datasets?page=1&pageSize=20` | READY 数据集分页列表，pageSize 为 1–200 |

用响应中的真实 importTaskId 查询，待 SUCCEEDED 后再用 datasetId 查询。失败时 datasetId 为 null，不发布部分有效行形成的“可用数据集”。HTTP 错误为 `{"error":{"code":"...","message":"..."}}`；异步失败使用任务内同结构 error。

## 检查及报告规则

- 优先严格解码 UTF-8 BOM / UTF-8，失败再尝试 GB18030；不忽略乱码。支持 CSV 中的 `YYYY-MM-DD`、`YYYY/M/D` 日期。
- 科学计数法流量使用 Decimal 直接解析，输出整数字节的十进制字符串，不经过 float。拒绝负数、非有限数和小数字节；为限制异常指数展开，整数部分最多 128 位。
- 身份键为 `(appId, 原始 fileKey 字符串, date)`。不删除协议、域名、查询参数，不合并疑似同源标识。同键同流量去重并计数；不同流量阻断整批。
- 无效日期、空身份、非法流量、App 不匹配、缺列或行列数不一致均失败。结构和编码错误也不会发布空数据集。
- 质量报告包含 `totalRows`、`validRows`、`duplicateRows`、`conflictRows`、`invalidRows`、`encoding`、`dateRange`、`appDateRanges`、`dailyRows`、`missingDates`、`missingDateCount`、单位确认、身份状态、完整性和 `warnings`。行数按 CSV 逻辑数据行统计；validRows 是成功解析的唯一观测数。
- 缺日按 App 各自首尾区间检查，未补零。missingDates 最多列 1,000 项；准确总数由 missingDateCount 保留，截断会标记 missingDatesTruncated。失败报告是已能解析部分的检查结果，不能据此认为整份文件有效；errorSamples 最多列 20 项。
- READY 只表示可读取，`completeness=UNKNOWN`；单位确认来源记录为用户提交声明，不能认证文件覆盖了完整 CDN 范围。`10066.csv` 的单位未确认且超出默认大小限制，不作为联调用例。

## 持久化与恢复

```text
runtime/predictor/
  imports/<importTaskId>/original.csv
  imports/<importTaskId>/task.json
  datasets/<datasetId>/original.csv
  datasets/<datasetId>/normalized.csv
  datasets/<datasetId>/metadata.json
  datasets/<datasetId>/quality.json
```

先在临时目录完整写入并 fsync，再原子发布目录；任务更新同样原子替换。未发布的临时目录不参与查询，不自动清理正式记录。文件和任务未持久化则不返回 202，存储不可用返回 503。

同一原始文件摘要、映射、单位、默认 App、来源、类型和解析版本复用相同不可变数据集。新批次独立导入，不追加旧观测。失败不修改上一份 READY 数据集。

启动时扫描持久任务：PENDING 继续处理；RUNNING 若已有完整发布的数据集则补齐成功，否则明确失败为 IMPORT_INTERRUPTED。已完成任务、数据集和报告无需重新导入即可查询。

## 自动验证

```bash
cd predictor
conda run -n cdn-predictor python -m pytest -q
```

测试使用隔离目录，不触碰 MySQL 和已有运行数据。`tests/test_import_http.py` 会启动真实 Uvicorn 进程及本机空闲端口，上传仓库 10350.csv，退出并重启 Python，核对任务、READY 数据集、质量报告及幂等结果。受限制环境可能需要允许本机端口及跨线程测试客户端运行。

现有 Starlette/httpx 测试客户端会发出弃用警告；本环节不为此安装或更换依赖。未进行浏览器自动化联调，不宣称完整预测链路已完成。

## 第④步：异步预测接口

| 接口 | 契约 |
| --- | --- |
| `POST /api/v1/predictions` | Java 提交 JSON；持久化后返回 HTTP 202 和任务视图 |
| `GET /api/v1/tasks/{taskId}` | PENDING / RUNNING / SUCCEEDED / FAILED；非成功时 runId=null，失败时 error 有错误码及原因 |
| `GET /api/v1/predictions/{runId}/items?page=1&pageSize=50` | 完整候选结果分页，page 从 1 开始，pageSize 为 1–200；rank 是全局名次 |

沿用 V1 设计和 Java 现有路径，无需调整代理。Python 预测幂等使用 JSON 内的 Java taskId，不要求额外 `Idempotency-Key` 请求头；相同 taskId 和六字段内容返回已有状态，同 ID 不同参数返回 409，不重新执行。字符串 ID 原样返回，持久路径使用 taskId 摘要，不直接拼接外部 ID。含保留字符的 taskId 在 HTTP 路径中需 URL 编码。

请求必须包含且只包含以下六字段，不能由调用方用 `mock=true` 改选执行模式。ID/App/版本不做数字转字符串等隐式转换：

```json
{
  "taskId": "task_from_java_example",
  "datasetId": "ds_replace_with_ready_dataset_id",
  "appId": "10350",
  "cutoffDate": "2026-10-06",
  "horizonDays": 7,
  "modelVersion": "contract-demo-v1"
}
```

以上是契约占位，需替换为实际导入返回的 READY datasetId 和 Java taskId。App 必须存在于数据集，截止日在该 App 的观测日期范围内；允许有缺日警告，不将缺日补零。格式错误直接 422，不创建任务；语义错误进入可查询的 FAILED，如 DATASET_NOT_FOUND、APP_MISMATCH、INVALID_CUTOFF_DATE、MODEL_VERSION_UNSUPPORTED、INSUFFICIENT_HISTORY、MODEL_ARTIFACT_CHANGED。重复失败任务仍返回原失败状态，需要新业务任务才能重做。

首次接收返回 PENDING，后续幂等请求可返回现有状态。后台阶段为 WAITING / VALIDATING / COMPUTING / COMPLETED / FAILED。成功任务含 `runId`；完整结果先原子发布，随后才把任务置为 SUCCEEDED。Python 的成功不代表 Java 业务成功。

结果响应包含 taskId、runId、datasetId、appId、cutoffDate、horizonDays、predictionStartDate、predictionEndDate、modelVersion、datasetSource、predictionSource、generatedAt、page、pageSize、total、items。所有流量为 B 的十进制字符串。只分页，不截取保存 Top50，也不计算 Java 的 Top50 合计或热点占比。空候选不造文件，空页返回 items=[]。

### Mock 规则与配置

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `PREDICTION_MODE` | `MOCK` | 仅允许 MOCK / MODEL；MODEL 执行真实 v1，失败不回退 Mock |
| `PREDICTION_MODEL_VERSION` | `contract-demo-v1` | 精确匹配请求版本，不按版本前缀选 Mock |
| `MOCK_DELAY_SECONDS` | `0` | 非负有限秒数；设置 100 可模拟长耗时，不影响 HTTP 查询 |
| `PREDICTION_TASK_TIMEOUT_SECONDS` | `180` | 从接收起计算的总截止时限，包含队列等待；超时为 TASK_TIMEOUT |

每份接收记录持久保存执行模式、模型版本、延迟及截止时间；配置变更不改变已接收任务的执行语义。单计算并发，其他计算任务留在持久化 PENDING，导入 worker 独立运行。延迟等待使用异步事件，退出时可快速停止；不会让 POST 请求挂起 100 秒。

Mock 仅替代计算：取指定 App、cutoffDate 及以前每个原始文件标识最近一次**已有观测**，以该观测流量乘 horizonDays 作为确定性的模拟窗口累计值。该公式是链路占位，不是真实预测算法、日均值估计或算法效果证据。缺失日不作为真实零；截止日之后的文件和数值不参与；URL 与无协议路径保持不同身份。horizonDays=1 对应下一自然日，7 对应后续连续七天（非自然周）；Java/前端不得再乘 7。

计算使用 Decimal，支持导入允许的最多128位整数字节，不经 float 中转。全部候选按累计模拟流量降序，同值按精确 fileKey 的 Unicode 顺序排序。输出 `predictionSource=MOCK`，数据来源沿用 READY 快照的 REAL/FAKE。score、fileType、fileSizeBytes、recentActualBytes、compressionStatus 均为 null，不生成未知业务事实；reason 明确标记模拟。

长耗时启动示例（沿用既有环境，不安装依赖）：

```bash
cd predictor
PREDICTION_MODE=MOCK MOCK_DELAY_SECONDS=100 PREDICTION_TASK_TIMEOUT_SECONDS=180 \
  conda run --no-capture-output -n cdn-predictor \
  python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Java 继续使用现有单次 HTTP 超时和主动轮询；100 秒是后台计算延迟，不要求 Java 把单次读取超时改成100秒。

### 任务、结果及恢复

```text
runtime/predictor/
  tasks/<SHA256(taskId)>/task.json
  runs/<runId>/result.json    # 保存本次全部候选和完整运行元数据
```

继续使用单实例、单 worker 和同一个持久卷。任务与结果不在内存中充当唯一事实来源。接收、更新和结果发布复用现有 fsync/原子替换；临时目录不参与查询。不自动删除旧任务/运行，失败任务不会覆盖其他运行。

重启后 PENDING 继续排队；RUNNING 如果有完整且契约匹配的已发布结果则补齐成功，否则明确为 EXECUTION_INTERRUPTED。损坏结果为 INVALID_RESULT，仅该任务失败，不阻断其他任务恢复。短暂存储异常返回503或形成明确计算失败；若成功写入响应丢失，使用原 taskId 重试不会创建第二份任务或结果。不存在完整运行返回404，不以空榜单掩盖失败。

### 回归验证

```bash
cd predictor
conda run -n cdn-predictor python -m pytest -q
# 仅快速回归（不重复实际100秒用例）：
conda run -n cdn-predictor python -m pytest -q --ignore=tests/test_prediction_http.py
```

完整测试包含真实 Uvicorn、10350.csv、实际100秒延迟和进程重启，因此耗时约两分钟；使用隔离临时目录及本机端口，不触碰现有运行数据、Java 或 MySQL。测试客户端已有的 Starlette/httpx 弃用警告仍保留，本轮不升级依赖。

Java 已实现结果校验、Top50 快照和 runId 查询。前端可查询已有 taskId/runId 并展示榜单，仍未新增导入质量报告、数据集选择、预测提交表单或模型评估看板。数据库无需新增迁移；测试不向现有业务 MySQL 写数据。

## 真实 v1 模式

从项目根目录进入 predictor，使用已有环境，不需要安装新依赖：

```bash
cd predictor
PREDICTION_MODE=MODEL PREDICTION_MODEL_VERSION=hotspot-simple-v1 \
  conda run --no-capture-output -n cdn-predictor \
  python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

服务地址或端口被已有进程占用时，应由运行者停止原进程后以新配置重启；不要同时启动两个进程使用同一个持久目录。根目录 `.env` 不会自动加载。已有 Java、Vue 启动方式和代理不变。

先按上面的 CSV 导入接口取得实际 READY datasetId，再向 Java 提交。以下 datasetId 是需替换的占位，任务请求不增加 resultMode 字段：

```bash
curl -i http://127.0.0.1:8080/api/v1/predictions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: real-10350-h7-v1' \
  -d '{"datasetId":"ds_replace_with_ready_dataset_id","appId":"10350","cutoffDate":"2026-10-06","horizonDays":7,"modelVersion":"hotspot-simple-v1"}'
```

将返回的 taskId 用于 `GET http://127.0.0.1:8080/api/v1/tasks/{taskId}`，仅业务状态 SUCCEEDED 后再查询 `GET http://127.0.0.1:8080/api/v1/predictions/{runId}`。Vue 可输入 taskId/runId，或打开 `http://localhost:5173/?taskId=实际编号#prediction`。日预测把 horizonDays 改为 1，并使用新的幂等键；参数改变不能复用旧键。

新结果保留 `datasetSource=REAL/FAKE`、`predictionSource=MODEL/MOCK`，并增加 `resultMode=REAL/MOCK`。真实 CSV 也能用于 Mock，不能由 datasetSource 推断结果模式。旧结果缺少 resultMode 时按其既有 predictionSource 兼容读取，原文件和历史 Java 快照不被改写。

真实实现从自己的 normalized.csv 读取指定 App，先裁剪至截止日，再处理疑似截断日、冷启动和参数选择。审阅配置的全部推广标签成熟后才可使用；更早的历史截止日以当时可见数据独立选型。候选使用近期自适应窗口，最多30日，按预测字节降序、精确文件标识稳定排序。输出所有候选，Java 只取前50，不凑数、不再次乘7。

`predictedBytes` 是历史观测强度外推的窗口累计估计，**未经完整 CDN 流量校准**；不是实测流量或已实现降本。整天无观测不补零；某个有记录日期内文件未出现，会降低其观测强度，不能解释为已确认真实零流量。score 无可靠来源时保留 null，文件类型、大小和压缩状态继续未知。仅基于流量 CSV 无法预测从未出现的文件。

Python 完整结果另保存 `modelInfo`：算法及配置摘要、选中方法和规格、参数来源、历史状态、选型证据及疑似截断日期。Java 仍保留原业务投影字段及 modelVersion；完整模型元信息可从 Python 的 `/api/v1/predictions/{runId}/items` 查询。新真实任务绑定服务启动时加载的算法/配置摘要；运行期间替换源文件后，新旧真实任务均失败为 MODEL_ARTIFACT_CHANGED，须重启服务后提交新任务，不把内存旧代码标成新文件摘要。重启后算法不同的旧排队任务同样失败；已成功快照仍可读取。无近期候选为 INSUFFICIENT_HISTORY；异常不产生 Mock 结果。

服务仅批准 `hotspot-simple-v1`。纳入的 hotspot 源码保留同事的离线工具与其他实验版本，服务不开放 v2/v3；未安装 LightGBM、NumPy 或修改现有环境。离线滚动回测可在 predictor 目录运行：

```bash
python -m hotspot.cli backtest --input ../datasets/10350.csv --app-id 10350 \
  --source-unit B --horizon-days 7 --model-version hotspot-simple-v1 \
  --output ../output/20261021_真实算法周回测_v01.json
```

该命令应使用上面的 cdn-predictor 环境；输出文件存在时先改用新版本名。回测指标为未来观测集合上的代理指标：Recall@50、对数相关度 NDCG、观测流量覆盖率、候选覆盖率，不等同于完整真实 Top50、设计中的线性 NDCG 或真实热点占比。没有接入在线评估接口和 Vue 指标展示。

### 回退 Mock

停止当前 Python 进程后，仍在 predictor 目录启动：

```bash
PREDICTION_MODE=MOCK PREDICTION_MODEL_VERSION=contract-demo-v1 \
  conda run --no-capture-output -n cdn-predictor \
  python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

新请求使用 `modelVersion=contract-demo-v1` 和新幂等键。已接收任务沿用其持久化策略，不因环境切换改模式；已成功的真实结果仍可查询，不删除数据。Mock 的 resultMode=MOCK，真实模型失败不会触发这一步操作。
