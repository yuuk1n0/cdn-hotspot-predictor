# 11 工程交付与集中验收

版本：20261021 v01。任务：T22，最后固定预留15分钟。本文是验收需求，不是已经通过的报告。

## 1. 功能目标

对应考核3.6，并核对1.2和3.1～3.5全部要求。交付可复演系统、启动说明、数据字典、接口契约、真实证据及精确缺口。半天只完成部分功能时，不能把方案、占位入口或Mock成绩当成正式完成。

## 2. 页面与交互

按演示路径操作：选App → 上传 → 导入报告 → 数据集选择 → 日/周任务 → 本次榜单 → 文件详情 → 登记 → 待处理补位 → 效果估算 → 回测 → 飞书预览/授权发送 → Agent问答。所有页面保留窗口、版本、数据/预测/证据来源；未实现入口明确未接入，不伪造成功交互。

## 3. 数据契约

本模块**不新增业务HTTP接口**；只消费前10模块接口。验收输入是目标环境、文件及App、实际返回的datasetId/taskId/runId等，不能照抄文档示例ID。输出为真实验收记录，建议保存在本版本目录后续新增的`acceptance.md`，附脱敏请求响应、截图或可复现日志路径。

可选机器可读证据格式（拟议文件格式，不是HTTP响应）：

```json
{
  "verifiedAt":"2026-10-21T01:00:00Z","codeCommit":"实际提交及工作区摘要",
  "moduleId":"01","taskId":"T01","status":"NOT_RUN",
  "datasetId":null,"runId":null,"predictionSource":null,"evidenceSource":null,
  "checks":[],"limitations":["此JSON仅为模板，尚未执行验收"]
}
```

status=`NOT_RUN/PASSED/FAILED/BLOCKED`；每个check应含实际操作、预期、实际结果、证据文件，不能只保存“通过”文本。JSON模板不可作为真实运行结果发布。

## 4. 服务职责

| 端 | 验收范围 |
|---|---|
| Vue | 浏览器真实交互、代理路径、来源标签、刷新、失败和迟到响应；单元测试/构建不等于浏览器通过 |
| Java | 持久任务、快照原子发布、业务校验、金额精度、来源/App、外部请求；H2不代替目标MySQL |
| Python | 原文件/标准化/质量、候选时间边界、完整结果、滚动报告、Mock/Model隔离 |
| MySQL | 只读检查目标库既有表/记录及关系；新迁移执行另获确认；不清库、重播旧data.sql或覆盖既有证据 |

T22仅补验收记录与必要独立验证脚本；发现业务缺陷回到所属单服务任务修复，不在验收时顺手重构三服务。

## 5. 最小实现方式

启动与测试以现有README为入口，不重新安装环境。已安装路径可供本机验证，其他环境须先定位现有工具：

```bash
# 在WSL仓库根目录；仅运行现有环境，不安装依赖
cd predictor
/home/yukino/miniconda3/envs/cdn-predictor/bin/python -m pytest -q --ignore=tests/test_prediction_http.py
```

```bash
cd backend
JAVA_HOME=/home/yukino/.sdkman/candidates/java/21.0.4-tem \
PATH=/home/yukino/.sdkman/candidates/java/21.0.4-tem/bin:/usr/bin:/bin \
mvn -o test
```

```bash
cd frontend
/home/yukino/.nvm/versions/node/v24.14.0/bin/node --test
/home/yukino/.nvm/versions/node/v24.14.0/bin/node node_modules/vite/bin/vite.js build
```

这些是后续验收命令，不表示本轮落盘重跑。Python快速命令跳过实际100秒HTTP用例；Java真实Python慢集成需按backend README显式设置解释器路径。慢回归只在相关改动或未解决疑点时追加，不机械反复全量测试。

| 集中检查 | 实际操作与预期 |
|---|---|
| P0网页闭环 | 浏览器上传10350→GET报告→选App→POST Java预测→GET Java任务→GET快照；记录实际ID及网络路径 |
| 日周与精度 | 下一自然日/后续七天；比对精确B字符串与Java快照，周值不再乘7 |
| 幂等与失败 | 同键重试同task；错App/坏CSV/依赖不可用/超时各有原因，不用Fake替代 |
| 过滤补位 | 60候选TEST样本、处理一条后第51补入；原快照不变 |
| 质量与收益 | 100MB→60MB=40%；1TB×0.8×0.4÷10^9×0.02−1−0.4=5元，标明测试估算 |
| 观察 | 未成熟/缺成本/无访问分别显示待观察/未知/失败，不直接认定有效 |
| 回测 | 特征不越截止日，标签不足不补零，Mock不正式选模；有逐折证据 |
| 外部 | CStore实际大小/访问、限流；飞书用户授权后测试群一条真实消息；Agent实际工具调用 |
| App与来源 | 至少两App的同名文件测试无串数据；REAL/FAKE、MOCK/MODEL、REAL/TEST分开 |

最少交付：根与服务启动README、本套架构/模块/API需求、实际数据字典、脱敏验收记录、未完成清单、演示步骤。真实算法比较报告和正式效果证据尚缺时明确列出；答辩PPT可后续生成，本轮不追加PPT任务。

## 6. Mock与真实实现边界

规划阶段曾运行Python90、Java核心31、前端38项及构建通过，只能引用为当时基线；后续工作区新增真实算法适配后不能自动沿用这些数字。历史MySQL/100秒验收见原记录，不归入本轮新结果。TEST数据、模板JSON、测试HTTP替身不能计入真实集成通过项。

## 7. 异常处理

工具/浏览器不可用时记录失败点和未验证项，不宣称视觉或端到端验收通过。数据库表缺失时只说明所需增量，不自动迁移。并行工作导致源码改变时记录新摘要与归属，不回滚其他修改。外部凭证、内部URL和签名URL不放入截图/日志；失败不导致删除历史数据。

## 8. 验收标准

1. 每项考核可在[module-boundaries.md](../module-boundaries.md)找到模块、最小实现、证据与缺口，无遗漏。
2. 完成模块有可复现操作和真实证据；未完成模块标NOT_RUN/BLOCKED/FAILED及原因，不把TODO当通过。
3. 新增文档链接、JSON示例、接口归属、任务数与240分钟预算一致；旧README不存在“已实现接口尚未实现”等冲突描述。
4. git检查确认本轮只新增/更新文档；此前已有业务改动原样保留；本轮无安装、迁移、外部发送、提交、推送。
5. 最终向用户简报架构、关键链路、21张编码任务、首先T01/T02、四小时风险、实际文件清单和下一步。
