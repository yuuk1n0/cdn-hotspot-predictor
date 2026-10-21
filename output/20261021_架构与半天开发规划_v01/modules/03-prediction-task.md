# 03 日周预测任务与网页闭环

版本：20261021 v01。任务：T02，仅Vue。Java/Python既有契约复用；与[01导入](01-data-import.md)衔接。现状和其他进行中真实算法工作见[架构](../architecture.md)。

## 1. 功能目标

对应考核1.2、2.2、3.1、3.2。用户无需命令行，能从READY数据集提交日/周预测并跟踪到MySQL榜单快照。本阶段默认Mock，不开发真实模型；不重新实现既有持久任务与轮询。

## 2. 页面与交互

- 在既有PredictionPanel前加提交区域，消费T01的dataset/App选择；截止日在该App元数据范围内，日/周对应horizonDays=1/7。
- 默认版本为当前部署确认的`contract-demo-v1`；不允许凭版本前缀猜算法，也不硬编码正在开发的真实模型为可用。
- 提交时冻结五字段和幂等键，按钮防双击。响应丢失后“重试本次提交”使用相同键和请求体；修改参数后才创建新提交。
- 202取得taskId后交给已有任务查询流程，每2秒GET Java；SUCCEEDED且有runId才GET运行快照。保留手动taskId/runId查询。
- 展示来源、截止日、目标窗口、模型、候选数、榜单数和精确流量；Mock显著标记。新请求失败时旧榜单标明“上一份”。

## 3. 数据契约

以下接口**已存在**，浏览器`/api/*`代理至Java`/api/v1/*`。

| Method + Path | 请求 | 返回 |
|---|---|---|
| POST `/api/v1/predictions` | JSON五字段；头Idempotency-Key:string 1～200字符 | HTTP202 `{taskId:string,status:string}`；同键同体仍返回原任务 |
| GET `/api/v1/tasks/{taskId}` | 路径string | 原五参数、taskId/status/stage、runId:string/null、datasetSource/predictionSource、error/lastDependencyError、时间字段等 |
| GET `/api/v1/predictions/{runId}` | 路径string | 不可变Top50快照，字段见下例 |

```json
{
  "datasetId":"ds_example","appId":"10350","cutoffDate":"2026-10-06",
  "horizonDays":7,"modelVersion":"contract-demo-v1"
}
```

五字段类型分别为string、string、date、int（仅1或7）、string。Python内部接收同五字段加Java的taskId，前端不提交给Python预测接口、不发送mock参数。

```json
{
  "taskId":"task_example","runId":"run_example","datasetId":"ds_example",
  "appId":"10350","cutoffDate":"2026-10-06","horizonDays":7,
  "predictionStartDate":"2026-10-07","predictionEndDate":"2026-10-13",
  "modelVersion":"contract-demo-v1","datasetSource":"REAL","predictionSource":"MOCK",
  "generatedAt":"2026-10-21T01:00:00Z","candidateCount":1,"top50Count":1,
  "top50PredictedBytes":"7000",
  "items":[{"rank":1,"fileKey":"example/a.mp4","predictedBytes":"7000","score":null,"reason":"模拟结果，仅验证链路","fileType":null,"fileSizeBytes":null,"recentActualBytes":null,"compressionStatus":null}],
  "hotspotTrafficRatio":{"metricBasis":"PREDICTED_TOP50_FORECAST","value":null,"numeratorBytes":"7000","denominatorBytes":null,"numeratorSource":"MOCK","denominatorSource":null,"reasonCode":"MOCK_RESULT_AND_NO_MATCHING_TOTAL"}
}
```

示例不代表真实测量。状态`PENDING/RUNNING/SUCCEEDED/FAILED`；非成功runId=null，FAILED才有终态error。进行中兼容字段`resultMode`如存在须匹配MOCK/MODEL来源；旧快照缺字段仍可读取，不删除其他工作新增的modelInfo等扩展。

## 4. 服务职责

| 端 | 本卡范围 |
|---|---|
| Vue | 改PredictionPanel.vue、predictions.js和必要App.vue及相关测试；复用现有校验、轮询、表格 |
| Java | **不修改**；既有任务表、客户端、调度器、精确求和和事务快照直接使用 |
| Python | **不修改**；既有接收、Mock、完整候选、持久化直接使用 |
| MySQL | **不修改**；prediction_task及prediction_run必须预先按已批准方式存在 |

## 5. 最小实现方式

先补POST封装和幂等状态，再加表单，最后将202的taskId交给原查询方法。可以在浏览器存储未确认提交的五字段与原键；刷新后只允许用户显式重试，不能自动POST。拿到taskId后保存编号并重新GET。前端5分钟等待上限只暂停GET，不改变Java任务。Java默认总时限180秒、单请求3秒连接/5秒读取照旧，不为长计算扩大同步等待。

## 6. Mock与真实实现边界

现有Mock对每个App/截止日前文件取最近一次已有观测，乘1或7输出窗口累计，按流量降序、fileKey稳定排序。score等未知字段为null。数据、HTTP、任务、存储是真实的；预测是模拟的。真实模型只替换Python内部计算并沿用输入/输出；未来必须核验已部署版本后才让表单选用，不改变Java与Vue调用流程。

## 7. 异常处理

缺dataset/App、非法日期/跨度本地阻断；服务端以现有规则最终校验。409幂等冲突提示改参数需新提交。语义错误可能由202后后台持久化为FAILED。Python短暂不可用由Java重试；页面展示lastDependencyError，不自行宣判失败。总超时显示TASK_TIMEOUT；查询断网仅暂停，可GET原编号。无任务表返回503，不伪造taskId。

## 8. 验收标准

1. 承接T01真实datasetId，在网页提交日预测，网络链路为Vue→Java→Python，最终自动显示同runId快照。
2. 改为周预测，窗口为cutoff+1至cutoff+7，B值与Java响应一致，不再乘7。
3. 双击和响应丢失重试沿用同键，查询得到同taskId；同键不同体返回409。
4. 错App任务明确失败，无榜单成功分支；Python不可用时可见依赖错误/最终超时，不用Fake表兜底。
5. 刷新成功运行后GET同快照；前端保留精确字符串及来源。核心表单/请求测试和构建通过，再做真实浏览器验收。
