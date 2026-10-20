# 第④步 Python 异步 Mock 实施计划

目标：仅实现 Python 预测接收、持久任务、Mock 执行及完整候选查询，不实现 Java Top50 或前端提交。依据 `materials/方案v1.md` §6.3–6.6，保持 Java 现有调用契约。

## 边界与选择

- 复用 `ImportStore` 的 READY 数据和原子写入函数，不新增数据库、迁移或依赖；只修改 `predictor/` 业务代码、测试和说明。
- 当前工作区有用户暂存的 Java/前端改动，原样保留；按用户增量开发要求在当前目录实施，不创建分支、不提交。
- 采用既有文件持久目录扩展：`tasks/<taskId摘要>/task.json`、`runs/<runId>/result.json`。不采用内存任务或新增 SQLite/MySQL 表。
- 模式由 `PREDICTION_MODE` 配置，默认 MOCK；模型版本精确匹配 `PREDICTION_MODEL_VERSION=contract-demo-v1`。MODEL 明确未实现，不按版本前缀选择算法。
- Mock 占位规则：只保留指定 App、日期不晚于 cutoffDate 的观测，每个原始文件标识取最近一次已观测字节值，乘 horizonDays 得到模拟窗口累计值。使用 Decimal，保持未知日期未知；不填零、不合并身份、不伪造业务属性。
- 相同数据及参数排序相同；按流量降序、精确 fileKey 升序。全部候选落盘，不只保存 50 条。
- 默认单计算并发；导入工作线程独立保留。延迟和总超时可配置，等待不阻塞 HTTP；发布完整结果后才暴露 runId 和 SUCCEEDED。
- PENDING 重入队；RUNNING 有完整结果则补成功，否则 EXECUTION_INTERRUPTED。存储异常不能返回假接收或留下永久 RUNNING。

## 实施清单

### 1. 输入与 Mock 计算

文件：`predictor/app/prediction.py`、`predictor/tests/test_prediction.py`。

- [ ] 先写请求严格类型、日期、App、模型模式、截止日隔离、精确 Decimal、稳定排序测试；运行确认失败。
- [ ] 实现 `PredictionRequest.from_json(body)`、`mock_items(rows, request)`。手算断言：最近观测 `10000000000000001` B、horizonDays=7 → `70000000000000007` B；截止日后新增文件不出现。
- [ ] 执行 `python -m pytest -q tests/test_prediction.py`。

### 2. 持久任务与结果

文件：`predictor/app/prediction_store.py`、`predictor/tests/test_prediction_store.py`。

- [ ] 先写持久接收、并发幂等/冲突、超过50候选分页、失败隔离、重启恢复及发布中断测试；确认失败。
- [ ] 实现 `PredictionStore.accept(body)`、`get_task(task_id)`、`process_next(stop)`、`get_items(run_id,page,page_size)`、`recover()`。结果与任务分别原子发布，任务响应不泄露内部保留字段。
- [ ] 执行存储测试；使用隔离目录，故障注入只替换实际文件写入边界。

### 3. HTTP 与异步生命周期

文件：`predictor/app/main.py`、`predictor/tests/test_prediction_api.py`、`predictor/tests/test_prediction_http.py`。

- [ ] 先写 POST 202 / GET任务 / GET结果、坏参数、错App、未知数据集及模型、延迟100秒、重启查询测试；确认预测路由尚不存在。
- [ ] 接入独立单并发 worker、配置延迟/总超时；复用现有错误结构和服务启动生命周期。
- [ ] 真实 Uvicorn HTTP 使用实际 `datasets/10350.csv`、临时持久目录及空闲端口，验证短Mock和实际100秒延迟Mock的相同契约与进程重启。

### 4. 收尾

- [ ] 更新 `predictor/README.md`：启动配置、请求字段、响应、Mock规则、文件路径和验收边界。
- [ ] 运行全部 Python 测试及 `git diff --check`；只读代码审查，修复有证据的问题。
- [ ] 不安装依赖、不执行迁移、不提交/推送；不自动进入第⑤步。Java 仍会因未实现聚合而失败，不能宣称全链路成功。
