# 第三阶段第②步：CSV 接收、检查与保存

本环节只实现 Python 导入，不包含预测任务、Mock、Java 编排或前端导入状态展示。第①步前端可继续提交 multipart；收到 202 仍只显示“已接收”，不会自动查询下面的导入结果。

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

第②步完成后停止，等待用户明确指示进入第③步。
