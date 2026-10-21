# CDN 热点文件算法（模块化独立运行版）

当前工作区没有交接文档所述的 `predictor/` 服务源码。实现位于 `hotspot/`，命令入口为 `hotspot/cli.py`；命令行生成完整候选结果 JSON，但不提供 HTTP 任务、幂等或分页接口。服务代码可用后，应通过 `hotspot.core.registry.get_algorithm(model_version)` 选择算法，再调用其 `configured_spec()` 和 `predict_items()`，由原服务继续负责任务和分页。

## 模块结构

| 目录 | 职责 |
| --- | --- |
| `hotspot/core/interface.py`、`hotspot/core/registry.py` | 统一算法接口和版本选择入口 |
| `hotspot/core/v1/` | v1 模型规格、生效配置、待选参数、选型规则、候选生成与预测排序 |
| `hotspot/core/v2/` | LightGBM 排序实验；沿用 v1 候选池，按切点滚动训练 |
| `hotspot/core/v3/` | LightGBM 零流量样本与 v1 排名融合实验 |
| `hotspot/training/` | 调用所选算法，编排历史参数选型与留出测试 |
| `hotspot/evaluation/` | 时间切分、固定参数回测、指标、报告与图表 |
| `hotspot/data/` | 文件级 CSV 读取和单位校验 |
| `hotspot/adapters/` | 完整预测结果的服务分页适配 |
| `hotspot/cli.py`、`hotspot/analysis.py` | 命令入口与跨模块流程编排 |
| `tests/` | 各模块的行为测试 |

`core/v1` 不依赖训练或评估。v1 的“训练”是基于过去切点选择参数，并不拟合模型权重。选型结果会写入报告；预测时传入 `--selection-file`，即可使用对应 App ID、预测周期和模型版本的选型结果。未传入时，已审查的 App 使用 `core/v1/config.py` 中的生效配置，新 App 根据可用历史自动选型或走冷启动回退。v2 和 v3 在每个预测切点只使用该切点之前已结束的未来标签窗口拟合 LightGBM 排序模型；`train` 对其运行历史选型评估，预测时会按截止日重新拟合，不保存通用模型文件。共享流程只依赖 `Algorithm` 接口：算法提供版本号、生效规格、待选参数、预测、选型规则和规格描述。预测结果保留全量候选的排名，以及 `fileKey`、十进制字符串 `predictedBytes` 等公共字段。

稳定调用入口位于仓库根目录的 `scripts/`。每个动作都有同名 `.sh` 和 `.ps1`，两种脚本接受相同的 CLI 参数；脚本会先切到仓库根目录，所以相对输入、输出路径都以仓库根目录为基准。脚本通过 `conda run -n cvte` 使用同一个 `cvte` 环境运行 v1、v2 和 v3，无须预先激活；也可从仓库根目录运行 `conda run -n cvte python -m hotspot.cli`。预测、训练、回测和分析均接受 `--model-version`，默认 `hotspot-simple-v1`；也可指定 `hotspot-lightgbm-v2` 或 `hotspot-lightgbm-v3`。

当前 Linux x86_64 环境由 Conda 管理，完整导出文件为 [`environment.yml`](environment.yml)。新机器可运行 `conda env create -f environment.yml`。手动重建与重新导出命令：

```bash
conda env remove -n cvte -y
conda create -n cvte python=3.12 -y
conda install -n cvte -c defaults 'lightgbm=4.7.0=cpu_py312*' -y
conda env export -n cvte | sed '/^prefix:/,$d' > environment.yml
```

| 动作 | Bash | PowerShell | 结果 |
| --- | --- | --- | --- |
| 参数选型（训练） | `scripts/train.sh` | `scripts/train.ps1` | 选型期指标与选中参数 JSON，不评价留出测试期 |
| 回测 | `scripts/backtest.sh` | `scripts/backtest.ps1` | 选型与留出测试期指标 JSON |
| 预测 | `scripts/predict.sh` | `scripts/predict.ps1` | 完整候选结果 JSON |
| 分析 | `scripts/analyze.sh` | `scripts/analyze.ps1` | 带报告、表格与图表的时间戳目录 |
| 测试 | `scripts/test.sh` | `scripts/test.ps1` | 单元测试结果与进程退出码 |
| v1/v2 对比 | `scripts/compare.sh` | `scripts/compare.ps1` | 相同留出切点上的两版指标 JSON |

仅输入文件级数据。`--source-unit B` 表示操作者已确认源数据单位为字节；尤其不要在未确认 `10066 (1).csv` 的单位时把结果用于业务发布。App 日总流量 CSV 不能充当文件级标签。

```bash
./scripts/train.sh \
  --input 'dataset/10350 (1).csv' --app-id 10350 --source-unit B \
  --horizon-days 7 --output results/10350-train.json

./scripts/predict.sh \
  --input 'dataset/10350 (1).csv' --app-id 10350 --source-unit B \
  --cutoff-date 2026-10-06 --horizon-days 7 \
  --task-id example-task --dataset-id example-dataset --dataset-source REAL \
  --output /tmp/10350-prediction.json

./scripts/backtest.sh \
  --input 'dataset/10350 (1).csv' --app-id 10350 --source-unit B \
  --horizon-days 7 --output /tmp/10350-backtest.json

./scripts/analyze.sh \
  --input 'dataset/10350 (1).csv' --app-id 10350 --source-unit B \
  --horizon-days both --output-root results

./scripts/test.sh
```

## 按 App ID 独立选型

每个 App ID 的次日和未来 7 日预测分别训练、回测。`train` 的 JSON 可直接作为该组合的预测参数文件；预测命令会核对 App ID、预测周期、模型版本和选型标签的结束日期。`backtest` 的 JSON 也可用作参数文件，但只有选型期参与参数选择，留出测试期仅用于验证。

```bash
./scripts/train.sh --input 'dataset/10350 (1).csv' --app-id 10350 \
  --source-unit B --horizon-days 1 --output results/10350-h1-selection.json

./scripts/predict.sh --input 'dataset/10350 (1).csv' --app-id 10350 \
  --source-unit B --horizon-days 1 --cutoff-date 2026-10-13 \
  --selection-file results/10350-h1-selection.json \
  --task-id example-app-selection --dataset-id example-dataset \
  --dataset-source REAL --output results/10350-h1-prediction.json
```

`10066 (1).csv` 的最后一天只有 124 条文件记录，回测时应传 `--exclude-last-days 1`。该文件的源流量单位尚未核实；基于其数值的排序实验可以先做，但 `predictedBytes` 和降本金额须在确认单位后使用。当前两组数据的独立选型与留出结果见 [按 App ID 优化记录](docs/appid-optimization.md)。

## 冷启动与热启动

预测以截止日之前的该 App ID 数据判断状态。少于 28 个观测日、最近 7 天少于 4 个观测日，或完整历史标签窗口不足（次日至少 10 个、未来 7 日至少 21 个）时，v1 使用 `last_day` 冷启动规则；v2/v3 在同样条件下回退到 v1 排序。候选不足 50 个会逐步扩展到最多 30 天。完全没有该 App ID 的历史时返回空清单和明确原因，因为没有可识别的文件候选。整天缺失不按零流量计入历史均值。

历史足够的新 App ID 自动使用最近 60 天的完整标签窗口，按 App ID 和预测周期独立选型，并与 `last_day` 基线在留出窗口比较；候选方案在命中率、NDCG 或可观测流量覆盖率上出现明显退步时保留基线。这个留出窗口参与了推广决策，后续新日期才是独立检验。已审查的 App ID 默认使用 [`config.py`](hotspot/core/v1/config.py) 中的配置；传 `--auto-select` 可用最近数据重新评估，传 `--selection-file` 可使用指定的历史选型文件。

输出的 `appHistory`、`parameterSource`、`selectionEvidence` 说明所用路径；每个文件有 `fileStartState` 和 `observedDaysLast30`。近 30 天只观测到 1 天的文件标为 `COLD`，推荐理由注明预测不确定。如果最后一天的文件数低于此前 7 天中至少 4 个观测日的中位数的 5%，且该中位数至少为 50，预测会排除这个疑似不完整的截止日，并在 `dataQuality` 中记录。完全没有出现在历史数据中的文件无法仅凭流量 CSV 预测，需额外的文件元数据或业务信号。

两个全新 AppID 的模拟 CSV 端到端验证见[测试记录](docs/simulated-new-app-validation.md)。

## LightGBM v2 实验

```bash
./scripts/compare.sh \
  --input 'dataset/10350 (1).csv' --app-id 10350 --source-unit B \
  --horizon-days 1 --output results/10350-compare-h1.json

./scripts/compare.sh \
  --input 'dataset/10350 (1).csv' --app-id 10350 --source-unit B \
  --horizon-days 7 --output results/10350-compare-h7.json

./scripts/predict.sh \
  --model-version hotspot-lightgbm-v2 \
  --input 'dataset/10350 (1).csv' --app-id 10350 --source-unit B \
  --cutoff-date 2026-10-06 --horizon-days 7 \
  --task-id example-v2 --dataset-id example-dataset --dataset-source REAL \
  --output results/10350-v2-prediction.json
```

`compare` 同时给出 v1 选型结果、与 v2 使用相同候选池的 v1 `last_day` 结果、v2 结果、差值和候选覆盖率与命中率之间的剩余空间。v2 不把未来未记录文件作为负例；训练只在未来有流量记录的候选文件中学习相对排序，因此评估仍是“可观测未来”代理指标。v2 的 `predictedBytes` 继续沿用 v1 历史外推值，LightGBM 只负责排序；`score` 仍为 `null`，不代表概率。没有足够历史训练组时，v2 会明确回退到 v1 排序。当前 10350 数据约 4,478 条文件日记录，训练样本高度相关，需以留出期效果判断是否值得继续扩展。

10350 的实际留出期对比见 [LightGBM v2 实验报告](results/10350-lightgbm-v2-report.md)；目前 v2 未优于 v1，默认版本仍为 v1。

## LightGBM v3 数据改进实验

针对 10350 文件日数据的稀疏性，v3 在已观测的完整未来日期内，把未出现的历史候选文件作为零流量训练样本，再将 LightGBM 排名与 v1 排名等权融合。它沿用 v1 候选池，`predictedBytes` 仍由 v1 产生；默认版本仍为 v1。分析、适用范围和逐切点回测命令见 [v3 实验报告](docs/10350-lightgbm-v3.md)。

PowerShell 用法相同，例如：

```powershell
.\scripts\train.ps1 --input 'dataset/10350 (1).csv' --app-id 10350 --source-unit B `
  --horizon-days 7 --output results/10350-train.json
.\scripts\test.ps1
```

`hotspot-simple-v1` 中 10066、10350 的 App 参数已按[本次独立回测](docs/appid-optimization.md)审查；其他 App 在预测时根据历史数据自动选型或使用冷启动回退。回测报告只评价未来**可观测文件**，并在参数选择期与测试期之间隔离标签窗口。缺日不会补为真实零；在观测数据不完整时，指标和 `predictedBytes` 都不能解释为完整真实流量。可用 `--exclude-last-days N` 排除疑似截断的末尾日期。

输出 JSON 保存全部已排序候选；服务接入时应原样保存，再用 `paginate_result()` 切页 1～200 条，保留全局排名和候选总数。`score` 暂为 `null`，因为本版没有独立校准的概率或跨 App 分数。

`analyze` 一次完成日/周预测回测与结果分析，在上海时区创建 `results/YYYYMMDD_HHMMSS/`。其中 `report.md` 是中文分析报告，`summary.json` 是含逐切点数据的完整结果，`metrics.csv` 是逐切点指标，`1/7-prediction.json` 和 `1/7-top50.csv` 保存最近可评价切点的预测与观测，`charts/` 下有方法比较、测试期趋势、预测与观测对照的独立 SVG 图表。未来未出现于观测表的文件在 CSV 中留空，图中标为“—”；匹配文件 WAPE 仅作有选择偏差的数值诊断。若数据末日疑似截断，可用 `--exclude-last-days N` 排除这些标签日期。
