# cdn-hotspot-predictor

基于历史 CDN 流量数据，实现热点文件日/周 Top50 动态预测、时间滚动回测与模型评估，结合压缩率、转码成本、存储成本及 CDN 流量费用进行预期净收益分析，并围绕数据看板、压缩效果追踪、飞书通知和 AI Agent 构建完整的降本业务闭环。

## 当前阶段

阶段 2 已实现 Top50 数据看板最小闭环：Vue 通过 Spring Boot API 查询 MySQL 中明确标识的 Fake 预测结果，并支持日期范围、App ID、日/周粒度、模型和压缩状态筛选。

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

Spring Boot 启动时会通过 `schema.sql` 和 `data.sql` 重建本阶段的 Fake 数据。根目录 `.env` 不会被 Java、Python 或 Vite 自动读取。

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

打开 `http://localhost:5173`，页面会通过 Vite 代理调用 Java 的 Top50 API。FastAPI 不参与阶段 2 数据链路。

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

- 后续 CSV 上传与导入状态查询由 Vue 直接访问 Python，文件不经过 Java。
- 后续预测任务由 Vue 提交给 Java，再由 Java 主动请求 Python。
- 当前只实现 Top50 查询看板，不实现详情、压缩操作、CStore、Python 预测、收益、通知或 Agent。
