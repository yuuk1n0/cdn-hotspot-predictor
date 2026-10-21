# cdn-hotspot-predictor

> 2026-10-21 文档核对：架构、现状边界和后续21个单服务编码任务见[开发计划](output/20261021_架构与半天开发规划_v01/development-plan.md)、[模块矩阵](output/20261021_架构与半天开发规划_v01/module-boundaries.md)及[架构](output/20261021_架构与半天开发规划_v01/architecture.md)。本次只更新文档，未执行功能开发或数据库迁移。

基于历史 CDN 流量数据，实现热点文件日/周 Top50 动态预测、时间滚动回测与模型评估，结合压缩率、转码成本、存储成本及 CDN 流量费用进行预期净收益分析，并围绕数据看板、压缩效果追踪、飞书通知和 AI Agent 构建完整的降本业务闭环。

## 当前阶段

已实现Python真实CSV导入、质量检查、持久异步Mock，以及Java持久任务、主动查询Python、结果校验和Top50事务快照。Vue可上传取得回执，或输入taskId/runId查询结果；导入监控、质量报告、数据集选择和预测提交表单仍待补齐。旧Fake筛选看板独立保留。真实算法适配另有未提交工作，本轮只记录进行中状态，不声明其已验收。

| 应用 | 技术栈 | 默认地址 | 健康检查 |
|---|---|---|---|
| 前端 | Vue 3 + Vite | `http://localhost:5173` | Top50 筛选、表格和分页 |
| 业务后端 | Java 21 + Spring Boot | `http://localhost:8080` | `GET /api/v1/top50` |
| 预测服务 | Python 3.11 + FastAPI | `http://localhost:8000` | `GET /api/v1/health` |
| 数据库 | MySQL 8 | 本地验证使用 `localhost:13306` | `top50_prediction` Fake 数据 |

## 环境准备

需要 Node.js 24、JDK 21、Maven、Conda 和 MySQL 8。当前阶段使用本地 MySQL，不依赖 Docker。当前工作区已准备隔离数据目录，可直接启动：

```bash
mysqld --no-defaults \
  --datadir="$PWD/runtime/mysql" \
  --socket=/tmp/cdn-hotspot-local-mysql.sock \
  --port=13306 \
  --bind-address=127.0.0.1
```

当前 `spring.sql.init.mode=never`，启动不自动执行 `schema.sql`、`data.sql` 或003/004迁移。旧 `data.sql` 含清理种子行为，不应为重启服务而重播。目标库需先核对既有表和已批准迁移；本轮不执行任何迁移。根目录 `.env` 不会被 Java、Python 或 Vite 自动读取。

## 启动预测服务

首次创建环境：

```bash
cd predictor
conda env create -f environment.yml
```

启动：

```bash
conda run --no-capture-output -n cdn-predictor \
  python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

验证：

```bash
curl http://localhost:8000/api/v1/health
```

## 启动业务后端

确认 `.env.example` 中对应的数据库、用户和密码已经在 MySQL 中创建，然后执行：

```bash
cd backend
MYSQL_DATABASE=cdn_hotspot \
MYSQL_USER=cdn \
MYSQL_PASSWORD=cdn_dev \
MYSQL_PORT=13306 \
mvn spring-boot:run
```

验证：

```bash
curl http://localhost:8080/api/v1/health
```

## 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。旧Fake榜单仍查询Java/MySQL；CSV经 `/data-api` 直接进入Python，任务/榜单经 `/api` 查询Java。Java直接主动请求Python，文件不经过Java。

## Top50 API

- `GET /api/v1/top50/options`：返回真实数据库中的筛选选项。
- `GET /api/v1/top50`：支持 `startDate`、`endDate`、`appId`、`granularity`、`modelVersion`、`compressionStatus`、`page`、`pageSize`。
- API 固定返回 `dataSource: "FAKE"`，表示预测结果为演示数据；MySQL 查询和前后端 HTTP 链路为真实实现。

## 测试

```bash
(cd predictor && conda run -n cdn-predictor python -m pytest)
(cd backend && mvn test)
(cd frontend && npm test && npm run build)
```

## 服务边界

- CSV上传已由Vue直接访问Python；Python已有导入状态、报告和数据集API，网页监控待T01补齐。
- Java已经接收预测任务并主动提交、轮询Python和保存快照；网页预测提交表单待T02补齐。
- CStore 文件访问已接入真实运行的 Top50 与详情：按需查询实际大小、预览和下载，访问 URL 不长期保存；默认单实例20QPS、并发2。配置及真实样本浏览器验收见[文件访问交付记录](output/20261021_CStore开发联调_v01/20261021_交付与验收_v01.md)。
- 压缩业务、回测业务界面、收益、通知及Agent等模块的状态以模块矩阵和各模块验收记录为准；CStore验收不代表这些业务已完成。
