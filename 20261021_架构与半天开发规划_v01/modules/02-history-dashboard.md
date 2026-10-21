# 02 历史流量与观测看板

版本：20261021 v01。任务：T03 Python → T04 Java → T05 Vue，逐卡实施。公共约定见[架构](../architecture.md)。本模块接口均为**拟议**。

## 1. 功能目标

对应考核2.1、3.1、3.3.3。按App查询真实标准化观测、日期缺口和窗口TopK，为看板、文件解释和Agent提供同一统计来源。当前没有此查询能力，不把旧Fake预测表当历史实测。

## 2. 页面与交互

从所选READY数据集进入历史区；App必选，可选fileKey。日视图选起止日；周视图选择开始日，结束日为开始日+6，明确连续七天。展示每日观测合计、观测文件数、窗口TopK、缺失日期和“仅已观测文件”说明。先用表格或简单SVG趋势，不引入新图表库。App改变清空旧文件选择，忽略迟到响应。

## 3. 数据契约

| Method + Path | 归属及请求 |
|---|---|
| GET `/api/v1/history` | Java对外；datasetId:string、appId:string、startDate/endDate:date必填；granularity=`DAILY/WEEKLY`默认DAILY；fileKey:string可选；topK:int 1～50默认50 |
| GET `/api/v1/datasets/{datasetId}/observations` | Python；除datasetId在路径外，其余参数与Java一致；Java只通过HTTP调用 |

DAILY允许最多366个日期；WEEKLY要求结束日=开始日+6。超出观测范围的日期保留无观测，不擅自缩小用户窗口。响应：

| 字段 | 类型与口径 |
|---|---|
| datasetId/appId/datasetSource/granularity | string，原样关联 |
| startDate/endDate | date，含首尾 |
| scope | 固定`OBSERVED_FILES` |
| observedTrafficBytes | string/null；仅已有记录合计；全窗口无记录为null |
| days | `{date, observedTrafficBytes:string/null, fileCount:int, status:OBSERVED/NO_OBSERVATION}[]` |
| items | `{rank:int,fileKey:string,observedTrafficBytes:string,observedDays:int}[]`，窗口累计降序，同值按精确fileKey排序 |
| missingDates | date[]，仅App整日无记录的日期；fileKey查询还需逐日区分该文件无观测 |
| completeness | 原数据集值，当前为UNKNOWN |
| hotspotTrafficRatio | `{value:null,reasonCode:"NO_CONFIRMED_APP_TOTAL"}` |

```json
{
  "datasetId":"ds_example","appId":"10350","datasetSource":"REAL",
  "startDate":"2026-10-01","endDate":"2026-10-02","granularity":"DAILY",
  "scope":"OBSERVED_FILES","completeness":"UNKNOWN","observedTrafficBytes":"1000",
  "days":[
    {"date":"2026-10-01","observedTrafficBytes":"1000","fileCount":1,"status":"OBSERVED"},
    {"date":"2026-10-02","observedTrafficBytes":null,"fileCount":0,"status":"NO_OBSERVATION"}
  ],
  "items":[{"rank":1,"fileKey":"example/a.mp4","observedTrafficBytes":"1000","observedDays":1}],
  "missingDates":["2026-10-02"],
  "hotspotTrafficRatio":{"value":null,"reasonCode":"NO_CONFIRMED_APP_TOTAL"}
}
```

示例是契约样例。若某日有一行显式0B，返回`"0"/OBSERVED`；无记录返回null，两者不同。fileKey精确匹配，某文件未出现不能推断其流量为零。

## 4. 服务职责

| 端 | 工作与单卡范围 |
|---|---|
| Python／T03 | 新增history查询函数与路由；只读ImportStore的normalized.csv，Decimal精确聚合；测试小样本 |
| Java／T04 | 新增history服务/控制器及Python客户端方法；校验App、窗口和返回关联，透传观测统计；不再次聚合 |
| Vue／T05 | 新增历史面板、请求封装、App.vue挂载；只格式化显示 |
| MySQL | 不新建观测表，不改既有任务或Fake表 |

## 5. 最小实现方式

Python对小快照一次扫描，同时按日/文件累加；fileKey条件在累加前应用，另保留App整日缺失判断。Java不缓存新事实、不从MySQL补值。周榜先按同一文件累计七天再排序，不把每日TopK名单求和当周TopK。文件详情若要“最近实际流量”，明确查询哪个日期；无观测显示未知，不能把最后一次观测冒充截止日实测。

## 6. Mock与真实实现边界

本模块只统计输入观测，算法不Mock。datasetSource沿用导入声明；FAKE样本也可用同一公式，但标记演示。行合计不是权威App总量；不在本次新增总量预测或以观测占比冒充真实热点占比。未来算法可以复用观测契约，不改变本模块语义。

## 7. 异常处理

404 DATASET_NOT_FOUND；422 APP_MISMATCH、INVALID_DATE_RANGE、DATASET_TYPE_UNSUPPORTED；503 PYTHON_UNAVAILABLE。无符合条件记录返回200、空items和null合计；依赖失败不能返回空数据成功。没有文件观测不等于文件不存在于CStore。

## 8. 验收标准

1. 导入两个App的FAKE小CSV，手算同一天和七天窗口的累计值、TopK排序，逐字段与Python及Java比较。
2. 删除样本中某一天记录后导入新快照，该日为NO_OBSERVATION/null；显式0B仍为OBSERVED/"0"。
3. 相同fileKey出现在不同App时只统计指定App；不存在App得到422。
4. 周窗口固定七天，排序基于窗口文件累计；不得平均日百分比。
5. 关闭Python，Java和页面显示依赖错误，不显示0B或Fake替代。当前页筛选上下文与结果一致。
