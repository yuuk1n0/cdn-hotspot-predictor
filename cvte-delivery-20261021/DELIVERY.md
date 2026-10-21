# 交付说明

本压缩包包含 CDN 热点文件预测项目的源码、配置、测试、示例数据、已有实验结果和文档。主说明见 README.md。

## 解压与运行

1. 解压压缩包，进入 cvte-delivery-20261021 目录。
2. 在 Linux x86_64 上运行 `conda env create -f environment.yml`。此环境文件包含 Linux 平台的精确构建号。
3. 运行 `./scripts/test.sh` 验证环境。预测、训练、回测和分析的命令示例见 README.md；Windows PowerShell 脚本位于 scripts/，但该 Conda 环境文件需按目标平台调整。

## 范围与限制

- 当前交付物是独立命令行算法模块，不含 README.md 所述的 predictor HTTP 服务源码。
- dataset/10066 (1).csv 的流量单位尚未核实；确认单位前请勿将对应 predictedBytes 或降本金额用于业务发布。
- results/ 包含已有实验报告和结果文件；新运行产生的输出可写入该目录。
- 打包前运行 `./scripts/test.sh`，30 项测试全部通过。
