# 05 时间滚动回测与模型评估

版本：20261021 v01。任务：T14 Python → T15 Java → T16 Vue。以下API、任务和报告均为**拟议**；本模块不认领其他线程的真实算法验收。

## 1. 功能目标

对应考核1.2、3.1、3.3.3及3.5模型优化。实现可复现的时间滚动执行与评价函数；本阶段预测用Mock，正式多模型选优待真实模型和可靠标签。流程可运行不等于已经证明预测有效。

## 2. 页面与交互

选择dataset、App、起止截止日、步长、1/7天和已部署模型版本；提交异步任务后查询状态及报告。逐折展示特征截止日、目标窗口、预测数、标签数、数据缺口、指标及不可计算原因；汇总展示有效/跳过窗口数，不把null均值当0。显著展示MOCK与OBSERVED标签。正式选模按钮在不满足条件时显示原因，不提供假“最优模型”。

## 3. 数据契约

| Method + Path | Java及Python | 请求／响应 |
|---|---|---|
| POST `/api/v1/backtests` | 两端同路径；Vue只调Java，Java转给Python | 头Idempotency-Key必填；下例请求；202 `{backtestId:string,status:"PENDING"}` |
| GET `/api/v1/backtests/{backtestId}` | Java主动查询Python | appId:string查询必填；状态、原参数、error、reportId:string/null |
| GET `/api/v1/backtests/{backtestId}/result` | Java主动取Python报告 | appId必填；成功报告，未完成409，未知404 |

Python持久化任务/报告为唯一回测任务来源；Java不另建任务表、不反向回调。重复键同参数返回同backtestId，不同参数409。

```json
{
  "datasetId":"ds_example","appId":"10350",
  "startCutoffDate":"2026-10-01","endCutoffDate":"2026-10-03",
  "stepDays":1,"horizonDays":7,"modelVersions":["contract-demo-v1"],
  "labelBasis":"OBSERVED_ONLY"
}
```

日期为date，stepDays为1～7整数，horizonDays为1/7，最多10个截止日和3个**实际支持**版本。labelBasis=`OBSERVED_ONLY/CONFIRMED`；CONFIRMED仅在数据元数据有可追溯的完整标签证明时可用，否则422 LABEL_NOT_CONFIRMED，不能由调用者自我声明完整。默认OBSERVED_ONLY。

报告字段：backtestId、datasetId、appId、datasetSource、predictionSource、horizonDays、modelVersions、labelBasis、evaluationPurpose、folds、summaries、formalSelectionEligible:boolean。每折及汇总指标：

```json
{
  "backtestId":"bt_example","datasetId":"ds_example","appId":"10350",
  "datasetSource":"REAL","predictionSource":"MOCK","horizonDays":7,
  "modelVersions":["contract-demo-v1"],"labelBasis":"OBSERVED_ONLY",
  "evaluationPurpose":"PIPELINE_VALIDATION","formalSelectionEligible":false,
  "folds":[{
    "cutoffDate":"2026-10-01","predictionStartDate":"2026-10-02","predictionEndDate":"2026-10-08",
    "modelVersion":"contract-demo-v1","predictionCount":50,"labelCount":42,
    "labelStatus":"OBSERVED_ONLY","precisionAt50":null,"ndcgAt50":null,
    "trafficCaptureAt50":null,"observedPrecisionAtK":0.6,
    "reasonCodes":["MOCK_PREDICTION","UNCONFIRMED_LABELS"]
  }],
  "summaries":[{"modelVersion":"contract-demo-v1","validWindowCount":0,"proxyWindowCount":1,"skippedWindowCount":0,"precisionAt50":null,"ndcgAt50":null,"trafficCaptureAt50":null,"observedPrecisionAtK":0.6}]
}
```

数值仅示意字段结构，不是正式算法成绩。状态为PENDING/RUNNING/SUCCEEDED/FAILED；labelStatus=`CONFIRMED/OBSERVED_ONLY/INSUFFICIENT`；evaluationPurpose=`PIPELINE_VALIDATION/FORMAL`。每项汇总应附自己的有效折数，不能用同一总数掩盖指标缺失。

## 4. 服务职责

| 端 | 单卡范围 |
|---|---|
| Python／T14 | 新增回测路由、任务与报告持久化；复用标准化输入、预测计算函数和原子写模式。每次最多一个回测任务，折内不并行，不引入通用调度框架 |
| Java／T15 | 新增HTTP代理业务，校验App/参数和响应归属；转发幂等键；每次查询主动请求Python，不计算指标、不新建Java回测表 |
| Vue／T16 | 参数表单、任务查询、逐折/汇总报告；只显示后端指标与限制 |
| MySQL | 不修改；未来App模型建议仅引用报告ID与证据，不复制全量回测数据 |

## 5. 最小实现方式

- 截止日按start到end、步长stepDays滚动；特征及候选只来自该App的date≤cutoff。Mock调用计算函数，不执行人为100秒延迟。
- 标签为cutoff后1或7天。窗口未成熟、缺日期时该折标INSUFFICIENT并跳过评价，保留原因；所有折跳过时报告仍可完成，但无成绩及明确原因。
- Precision@50：与**已确认实际Top50**交集数/预测名单实际条数；空名单null，同时报告预测/标签条数。不足50必须可见。
- NDCG@50：实际窗口B作为线性相关度，折扣log2(rank+1)，理想排序来自固定评价候选全集；缺所需流量或IDCG=0则null，不把未观测文件填0。
- 流量捕获率：预测名单实际B之和/真实Top50实际B之和；缺标签、缺名单内实际B或分母0则null。它不是除以App总流量的热点占比。
- 当前未确认完整数据可计算`observedPrecisionAtK`：对已观测窗口TopK的成员交集/预测条数，仅作为观测代理指标；不输出同名正式指标冒充完整标签。
- 确认完整日Top100后可确认日Top50成员，但未入榜流量仍未知；周累计Top100联集只能是观测代理周榜，不自动升级CONFIRMED。
- 各指标只对自身非null折算术平均，并报告折数；各模型比较必须同App、跨度、快照及相同有效截止日集合。本阶段只有一个Mock版本，不编造第二个方案或排行榜。
- 后台默认180秒总期限，超时明确失败；PENDING可恢复，RUNNING无已发布完整报告则重启后明确中断失败。单独backtests目录保存请求/报告，不改既有预测六字段契约。

## 6. Mock与真实实现边界

滚动、校验、报告、指标函数是真实逻辑；MOCK预测报告evaluationPurpose为PIPELINE_VALIDATION，即使测试标签完整也不能参与正式选模。真实算法未来沿用同一预测请求和条目契约。FORMAL还要求REAL数据、MODEL预测、足够的可比成熟标签及至少两个已验证真实方案；门槛和报告由模块10引用，不能在Agent里重新计算。

## 7. 异常处理

422用于错App、非法范围、超10折、未知模型、LABEL_NOT_CONFIRMED；409用于幂等冲突/结果未完成；404用于未知ID；503用于依赖/存储不可用。模型异常不能回退Mock后仍标MODEL。缺标签为报告内显式不可计算，不属于“准确率0”。损坏报告不得返回空成功。

## 8. 验收标准

1. 用独立测试fixture手算Precision、DCG/IDCG和捕获率，验证分母0、空预测、缺流量为null；该fixture不代表业务标签已认证。
2. 向cutoff之后追加新文件或改其流量，之前一折预测不变；只影响相应目标标签。
3. 用10350已成熟小窗口跑Mock流程；显式MOCK、OBSERVED_ONLY、formalSelectionEligible=false；缺日窗口跳过而不补0。
4. Java提交及时202，随后只主动GET；原键重试得到同backtestId；进程重启后成功报告仍可查，中断任务有终态。
5. 请求未部署版本或伪称CONFIRMED得到明确422，不伪造比较结论；前端逐折数与汇总有效折数一致。
