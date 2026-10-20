# 第④步 Python 异步 Mock 验收

## 范围与改动

仅开发 Python，未实现 Java Top50 或修改前端。保留原有 CSV 导入、质量报告和健康检查。

- `predictor/app/prediction.py`：严格请求契约、可替换的确定性 Mock 计算。
- `predictor/app/prediction_store.py`：持久幂等任务、单并发执行、原子完整结果发布、分页与恢复。
- `predictor/app/main.py`：三个预测接口、独立计算 worker、延迟/总超时配置。
- `predictor/tests/test_prediction.py`、`test_prediction_store.py`、`test_prediction_api.py`、`test_prediction_http.py`：计算、存储、接口和真实进程验收。
- `predictor/README.md`：运行配置、接口、Mock规则及验收边界。

复用 READY 数据集的 `normalized.csv`、元数据及现有原子写入/fsync 函数；没有新增数据库表、依赖或基础设施。本代理未执行安装、迁移、清理、提交或推送。工作期间外部 HEAD 更新为 `80bcc34`，未回退或修改该 Git 操作。

## 接口及运行

```text
POST /api/v1/predictions
GET  /api/v1/tasks/{taskId}
GET  /api/v1/predictions/{runId}/items?page=1&pageSize=50
```

请求为 taskId、datasetId、appId、cutoffDate、horizonDays、modelVersion；Python 使用 Java 原 taskId 持久幂等，不要求预测 Idempotency-Key 请求头。同 ID 同请求返回已有任务，同 ID 不同请求返回409。首次202表示已持久接收，非计算完成；只有完整结果发布后才提供 runId。

沿用原启动方式；可加 `PREDICTION_MODE=MOCK MOCK_DELAY_SECONDS=100 PREDICTION_TASK_TIMEOUT_SECONDS=180`。默认模型版本 `contract-demo-v1` 精确匹配；MODEL 返回 MODEL_NOT_IMPLEMENTED。启动和 curl 字段说明见 `predictor/README.md`。

窗口为 cutoffDate 后1天或连续7天；Python输出的 predictedBytes 已是目标窗口累计值，Java/前端不得再乘7。Mock计算仅作链路占位：截止日以前最近一次观测流量乘目标天数，不是算法预测能力证据。未知业务字段保持null，不填补缺日或合并文件身份。

## 已运行验证

最终命令：在 `predictor/` 使用现有环境执行 `python -m pytest -q -s`。

结果：**92 passed，1 warning，108.42 秒**。警告是现有 Starlette/httpx 测试客户端弃用提示，未为此安装或升级依赖。`python -m compileall -q predictor/app predictor/tests`、`git diff --check` 也通过。

覆盖：原有导入/健康检查；参数严格类型；数据集/App/截止日/模型错误；同键并发幂等、冲突及重复结果抑制；截止日隔离；精确大整数字节计算与排序；全部候选保存和全局分页；单计算并发；100秒异步等待；任务总超时；中断任务明确失败；发布完成但成功状态未更新时修复；坏结果仅失败自身、不阻断下一任务；失败不覆盖其他有效运行；已完成任务及结果重启查询。

### 真实文件与 HTTP 证据

实际检查了 `datasets/10350.csv` 的 GB18030 表头：`文件标识,日期,当日流量（B）,AppID`；实际行 App 为10350。未从文件名推断业务字段。

测试通过真实 Uvicorn 和本机 HTTP 上传原 CSV，在隔离目录导入。截止日2026-10-06、后续连续7天、REAL输入、MOCK预测，两种用例均保存并分页返回 **930 个完整候选**。成员集合由测试独立读取原 CSV 核对，截止日之后新增身份不进入候选。不是原型固定榜单，也不只保存前50。

共同 datasetId：`ds_a2740c2769b0e4e56d95c5a6f7022457391aa48479b1eed8c3b00e231c057c3a`。

| 用例 | taskId | runId |
| --- | --- | --- |
| 短 Mock | task_real_short | run_48a9db89f45df59a80f25b28d8f7d1f742f7a39664b502ed611c3e7bc751e036 |
| 实际100秒 Mock | task_real_100s | run_75c7c1a04f1d5c7a286bdcc9c196149d454c1b94a91f7bf35b7dc87daea58319 |

最终100秒用例实测：POST202返回 **0.004秒**，执行完成后查询耗时 **100.267秒**；运行期间任务为RUNNING、runId=null，健康/数据集查询可用。两种用例完成后退出并重启Python，任务和完整候选查询与重启前一致；重复提交仍返回原runId。以上ID来自测试隔离目录，不能假定已存在于默认runtime目录。

所有测试 Uvicorn 进程已随测试退出。本轮挂起的受限环境 pytest 已停止；测试不连接 Java/MySQL，不触碰既有 runtime 数据。

## 未验证与停止点

- 未运行 Java → Python → Java Top50 跨服务联调；没有浏览器验收。
- 第⑤步仍待开发。当前 Java 读到 Python成功后会明确返回 AGGREGATION_NOT_IMPLEMENTED；本轮不改变此行为，不宣称全链路成功。
- 10066.csv 单位仍待确认，未用于 Mock 联调；不包含真实算法效果、回测、CStore、压缩或收益验证。
- 存储沿用单实例、单worker约束，不支持多个进程共享目录执行；没有引入分布式调度。

使用 Skills：`superpowers:using-superpowers`、`superpowers:brainstorming`、`superpowers:writing-plans`、`superpowers:test-driven-development`、`superpowers:systematic-debugging`、`superpowers:requesting-code-review`、`superpowers:verification-before-completion`。依据用户已给定设计直接推进普通实现选择；TDD与审查促成了大整数排序精度和损坏结果恢复的回归修复。

第④步完成并停止，等待用户明确指示进入第⑤步。
