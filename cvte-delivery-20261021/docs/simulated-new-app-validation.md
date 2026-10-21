# 两个未知 AppID 的模拟 CSV 验证

2026-10-21 使用 [`generate_simulated_csv.py`](../scripts/generate_simulated_csv.py) 生成两份确定性的文件级 CSV，随后通过真实 CLI 读取、回测和预测。可运行 `python3 scripts/generate_simulated_csv.py` 重新生成到 `/tmp/cvte-unknown-app-simulation/`。

| AppID | 数据特点 | 日 Top50 留出 Recall | 7 日 Top50 留出 Recall | 最终预测路径 |
| --- | --- | ---: | ---: | --- |
| 99001 | UTF-8，连续 100 个完整日期，每天 100 个文件；稳定、趋势、周周期和新文件；另有 3 条记录的截断末日 | 65.0%（30 个切点） | 85.8%（28 个切点） | 热启动自动选型：日 `last_day`、周 `mean_7`；排除截断末日 |
| 99002 | GB18030 中文表头，120 天中有 105 个记录日期，每天约 11～33 个文件；缺日、突发和新文件 | 82.6%（30 个切点） | 48.3%（17 个切点） | 热启动自动选型的近期留出保护保留 `last_day` 基线 |

`99001` 预测输出有 110 个日候选、150 个周候选，截断日的 3 个文件均未进入候选。`99002` 两个周期各有 51 个候选。两份 CSV 在 2026-01-10 的早期截止日分别只有 10 和 8 个观测日，均进入冷启动 `last_day` 回退；候选中未出现截止日之后才生成的新文件。所有输出排名连续、预测字节数非负。

这些指标来自人为生成的数据，只检验数据读取、无未来泄漏、分 AppID 选型、冷/热切换、末日截断识别与候选输出链路，不能推断最终验收 CSV 的准确率。`99002` 的 7 日候选覆盖率约 49.1%，表明突发新文件仍是主要限制。

复现示例：

```bash
python3 scripts/generate_simulated_csv.py
python3 -m hotspot.cli backtest \
  --input /tmp/cvte-unknown-app-simulation/dense_new_app.csv \
  --app-id 99001 --source-unit B --horizon-days 7 --exclude-last-days 1 \
  --output /tmp/cvte-unknown-app-simulation/backtest-99001-h7.json
python3 -m hotspot.cli predict \
  --input /tmp/cvte-unknown-app-simulation/dense_new_app.csv \
  --app-id 99001 --source-unit B --horizon-days 7 --cutoff-date 2026-04-11 \
  --task-id simulated-99001-h7 --dataset-id simulated-99001 \
  --dataset-source FAKE \
  --output /tmp/cvte-unknown-app-simulation/predict-99001-h7.json
```
