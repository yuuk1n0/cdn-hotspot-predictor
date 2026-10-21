# 10 实时Agent与按App模型配置

版本：20261021 v01。任务：T19 Java配置 → T20 Java Agent → T21 Vue。此处“两个Java任务”分别开发配置与对话，不合成一次大改。全部新增接口为**拟议**。

## 1. 功能目标

对应考核3.5：实时回答热点、推荐原因、周流量、压缩效果、收益、模型表现和逾期问题；周期性按App评估模型，提供优化方向，并在真实证据合格且用户确认后切换配置。当前Mock不能证明哪个真实模型最佳；不得为演示捏造替换结果。

## 2. 页面与交互

App为必选上下文；用户可选业务日、1/7天、run。聊天响应展示证据卡：实际数据日期、窗口、版本、来源、工具/接口、关键口径与不确定原因。提问“今天”默认上海实际日期，不把所选历史run冒充今天。

配置区分日/周版本、通知开关、处理时限与评估建议。模型建议先预览；正式切换必须用户独立点击确认，不由聊天回复或工具自动执行。无合格候选时显示阻断原因。第一版不加流式输出、持久聊天历史、向量库或多Agent编排。

## 3. 数据契约

| Method + Path | 输入类型与用途 | 输出 |
|---|---|---|
| GET `/api/v1/app-configs/{appId}` | 路径string | appId、version:int、dailyModelVersion/weeklyModelVersion:string、availableModels数组、通知配置、modelReviewEnabled:boolean、latestSuggestion |
| PUT `/api/v1/app-configs/{appId}` | expectedVersion:int；notificationsEnabled:boolean、destinationId:string、handlingHours:int 1～168、modelReviewEnabled:boolean可更新 | 更新后配置；不能经此接口改模型版本绕过确认 |
| POST `/api/v1/app-configs/{appId}/model-suggestions` | horizonDays:int1/7，backtestId:string可选 | suggestionId、evidence、reasonCodes、canSwitch、targetModelVersion:string/null |
| POST `/api/v1/app-configs/{appId}/model-switches` | suggestionId:string、expectedVersion:int、confirmed:boolean必须true | activeVersion、changedAt、依据；门槛不满足409 |
| POST `/api/v1/agent/chat` | 下例 | answer:string、context:object、evidence:object[]、uncertainties:string[]、suggestionId:string/null |

```json
{
  "appId":"10350","businessDate":"2026-10-07","horizonDays":7,
  "runId":"run_example","message":"为什么推荐这个文件，它未来一周预计有多少流量？",
  "fileKey":"example/a.mp4"
}
```

```json
{
  "answer":"所选历史运行覆盖2026-10-07至2026-10-13，contract-demo-v1输出为模拟结果；该文件窗口累计7000B，仅用于链路演示，不能作为真实预测依据。",
  "context":{"appId":"10350","businessDate":"2026-10-07","horizonDays":7,"runId":"run_example","modelVersion":"contract-demo-v1"},
  "evidence":[{"tool":"explainFile","runId":"run_example","fileKey":"example/a.mp4","predictionSource":"MOCK","predictedBytes":"7000","dataStartDate":"2026-10-07","dataEndDate":"2026-10-13","basis":"WINDOW_CUMULATIVE_BYTES"}],
  "uncertainties":["MOCK预测不代表真实算法效果"],"suggestionId":null
}
```

省略businessDate时使用上海当日；缺run时只查询与App、日期、跨度匹配的运行，无法匹配返回无数据，不悄悄选旧run。JSON示例是期望契约，不是实际大模型输出。

最少工具集，由Java注册固定名称、JSON参数及结构化返回：

| 工具 | 业务数据来源与输出 |
|---|---|
| getTopHotspots | 模块04；返回前10、run/日期/跨度/版本/来源 |
| explainFile | 模块03原始候选/模块04及02；返回理由、窗口预测B、指定日期实测或未知 |
| getCompressionEffects | 模块08；返回观测/成本/估算及证据，不能大模型重算金额 |
| getModelMetrics | 模块05；返回逐折/汇总、模型、Mock/代理/正式资格 |
| getOverdueFiles | 模块09；返回绑定run的逾期项、dueAt和当前时刻 |
| getModelSuggestion | T19；读取/生成建议，不执行模型写操作 |

## 4. 服务职责

| 端 | 单卡范围 |
|---|---|
| Java／T19 | 复用T17的app_config；新增配置/建议/确认服务，按App存日周版本、有限建议及确认记录；单实例每日上海10:00对开启评估的App运行一次检查 |
| Java／T20 | agent控制器、兼容聊天服务薄HTTP客户端、有限工具调用循环；工具调用既有Java业务Service，不绕过口径、不直接读其他模块Repository |
| Vue／T21 | 聊天及证据卡、配置区和独立确认按钮；不得直接持有模型密钥或自行发Webhook |
| Python | 只提供既有预测及模块05回测；本模块不增加真实模型、目录服务或自动改代码能力 |
| MySQL | app_config已有行：app_id二进制精确主键、settings_json、version、updated_at_ms；建议/报告ID/确认人声明和时间存JSON；不增聊天库或模型注册中心 |

## 5. 最小实现方式

**Agent：**使用用户可提供且支持工具调用的聊天协议。每次用户请求最多4轮模型往返、每轮最多3个工具，每个工具参数和结果验证App与上下文；整体超过60秒明确超时。工具调用由Java执行，模型只能组织事实，不得输出可执行SQL、代码修改或绕开白名单。密钥在服务端环境；错误信息不回传密钥。

业务数字至少有一次相关实时工具调用才能回答；结构化evidence由Java从真实工具结果生成，不能依赖模型自行编写。工具失败/缺数直接进入uncertainties，禁止回退固定业务答案。金额、排名、指标用工具结果的格式化值，提示词要求按原数据引用；遇到模型回答与证据明显矛盾时拒绝发布该数字并返回事实摘要。该最小校验不等于通用事实证明体系。

**App配置与优化：**本部署只列实际确认支持且有成功验证运行的模型；当前Mock默认`contract-demo-v1`。源码基线中Python只配置一个版本，进行中的真实适配是否已验收另行核对。不能假称已有多版本动态分派；无两个同时受支持的真实候选时返回`MODEL_SWITCH_NOT_AVAILABLE`，当前配置不变。

周期检查复用同一回测报告，生成“缺真实模型/缺完整标签/需要补充数据”等建议；不强制每天重新训练或重新跑全量任务。未来允许正式切换的最小工程门槛：同App、同跨度、同快照，至少两个当前部署支持的MODEL版本、REAL数据、CONFIRMED标签，至少7个相同成熟截止日且所需指标均可算；7是本方案工程默认，不是题设准确率门槛。按平均NDCG@50比较，严格提高才提出更换；NDCG相同不更换，可展示Precision作解释。需保留原/目标版本、报告和窗口证据。

切换接口重新验证上述门槛、expectedVersion、建议所属App及当前部署支持情况，且confirmed=true后仅更新该App对应日/周版本，不覆盖历史任务或另一App。不执行模型代码、依赖安装、部署或自动训练。后续预测表单从配置取版本，但既有五字段接口不变。没有真实多模型能力时，只验收阻断和建议，不声称正式优化已完成。

## 6. Mock与真实实现边界

模型回答不是固定Mock，须真调用模型服务并用实时业务数据。MOCK运行允许演示问答，但准确率问题明确“尚无正式评估”；Mock指标不产生可切换正式建议。离线工具fixture仅为测试，不算实时Agent完成。后续真实算法用同预测/回测契约接入，不在Agent内重新算预测或评价。

## 7. 异常处理

422缺App/日期或工具参数不合法；409版本冲突、MOCK_EVIDENCE_ONLY、INSUFFICIENT_COMPARABLE_WINDOWS、MODEL_SWITCH_NOT_AVAILABLE；503模型/工具依赖不可用；504整体超时。无法查到今天、昨天或一周数据时明确无数据；未入Top100说未知，不编0。工具请求其他App拒绝执行。confirmed=false不写配置；建议失效需重查，不复用过期确认。

## 8. 验收标准

1. 逐一问：Top10、某文件为何推荐及周流量、压缩效果与估算、昨天模型准确率、最好模型、哪些逾期；核对对应工具与API结果，回答含日期/跨度/版本/口径。
2. 用历史Mock run提问“今天”，不得把历史日期改为今天；没数据明确说无数据。
3. 断开某工具或模型服务，不返回固定榜单/金额；证据卡与错误原因可见。
4. 相同fileKey的两App提问/配置互不影响；模型企图传其他App或调用非白名单工具时被拒绝。
5. Mock报告只能生成缺口建议，正式切换409且配置不变；测试fixture满足真实门槛时验证独立确认和版本校验，但不把fixture视为实际模型已上线。
6. 用测试时钟触发周期检查，记录建议更新时间与依据；默认未启用的App不自动调用外部模型或改配置。
