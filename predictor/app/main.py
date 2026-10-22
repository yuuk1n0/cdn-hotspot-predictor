import asyncio
import json
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Body, FastAPI, File, Form, Header, Query, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .csv_import import ImportConfig, ImportProblem
from .import_store import ImportStore
from .prediction_store import PredictionStore
from .alerts import JavaAlertSink
from .mysql_store import MySqlDataStore, MySqlSettings
from .history import observations
from .model_evidence import model_evidence
from .model_review import review_model
from .algorithm_adapter import MODEL_VERSION
from .upload_stream import stage_upload
from hotspot.core.v1.config import config_for
from hotspot.core.v1.spec import ModelSpec


DEFAULT_IMPORT_MAX_BYTES = 200 * 1024 * 1024


def resolve_import_max_bytes(explicit: int | None) -> int:
    if explicit is not None:
        return explicit
    return int(os.getenv("IMPORT_MAX_BYTES", str(DEFAULT_IMPORT_MAX_BYTES)))


def create_app(data_dir=None, *, max_file_bytes=None, max_rows=None, prediction_mode=None,
               prediction_model_version=None, mock_delay_seconds=None, prediction_timeout_seconds=None,
               data_source=None, alert_sink=None):
    root = Path(data_dir or os.getenv("PREDICTOR_DATA_DIR", Path(__file__).resolve().parents[2] / "runtime" / "predictor"))
    max_file_bytes = resolve_import_max_bytes(max_file_bytes)
    max_rows = max_rows if max_rows is not None else int(os.getenv("IMPORT_MAX_ROWS", 1200000))
    if max_file_bytes <= 0 or max_rows <= 0:
        raise ValueError("导入文件和行数上限必须大于零。")
    source_mode = os.getenv("PREDICTION_DATA_SOURCE", "FILES").strip().upper()
    if source_mode not in {"FILES", "MYSQL"}:
        raise ValueError("PREDICTION_DATA_SOURCE 仅支持 FILES 或 MYSQL，不允许静默降级。")
    if data_source is None and source_mode == "MYSQL":
        data_source = MySqlDataStore(MySqlSettings.from_env())
    if alert_sink is None:
        alert_sink = JavaAlertSink.from_env()
    store = ImportStore(root, max_rows, data_store=data_source, alert_sink=alert_sink)
    predictions = PredictionStore(
        root, store, mode=prediction_mode if prediction_mode is not None else os.getenv("PREDICTION_MODE", "MOCK"),
        model_version=prediction_model_version if prediction_model_version is not None else os.getenv("PREDICTION_MODEL_VERSION", "contract-demo-v1"),
        delay_seconds=mock_delay_seconds if mock_delay_seconds is not None else float(os.getenv("MOCK_DELAY_SECONDS", "0")),
        timeout_seconds=prediction_timeout_seconds if prediction_timeout_seconds is not None else float(os.getenv("PREDICTION_TASK_TIMEOUT_SECONDS", "180")),
        data_source=data_source,
    )

    @asynccontextmanager
    async def lifespan(app):
        if data_source is not None:
            await asyncio.to_thread(data_source.check_schema)
        await asyncio.to_thread(store.initialize)
        await asyncio.to_thread(predictions.initialize)
        stop = asyncio.Event()

        async def worker():
            while not stop.is_set():
                try:
                    processed = await asyncio.to_thread(store.process_next)
                except OSError:
                    logging.getLogger(__name__).exception("import worker storage unavailable")
                    processed = False
                if not processed:
                    try:
                        await asyncio.wait_for(stop.wait(), timeout=0.1)
                    except TimeoutError:
                        pass

        task = asyncio.create_task(worker())

        async def prediction_worker():
            while not stop.is_set():
                try:
                    processed = await predictions.process_next(stop)
                except OSError:
                    logging.getLogger(__name__).exception("prediction worker storage unavailable")
                    processed = False
                if not processed:
                    try:
                        await asyncio.wait_for(stop.wait(), timeout=0.1)
                    except TimeoutError:
                        pass

        prediction_task = asyncio.create_task(prediction_worker())
        try:
            yield
        finally:
            stop.set()
            await asyncio.gather(task, prediction_task)

    app = FastAPI(title="CDN Hotspot Predictor", version="0.1.0", lifespan=lifespan)
    app.state.imports = store
    app.state.predictions = predictions
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[os.getenv("FRONTEND_ORIGIN", "http://localhost:5173")],
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["*"],
    )

    @app.exception_handler(ImportProblem)
    async def import_problem(_request, error):
        return JSONResponse(status_code=error.status, content={"error": error.as_dict()})

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request, _error):
        return JSONResponse(status_code=422, content={"error": {"code": "INVALID_REQUEST", "message": "请求缺少必要字段，或字段类型及取值不合法。"}})

    @app.exception_handler(OSError)
    async def storage_unavailable(_request, _error):
        return JSONResponse(status_code=503, content={"error": {"code": "STORAGE_UNAVAILABLE", "message": "持久存储暂不可用；未确认本次操作，请使用原编号或原幂等键重试。"}})

    @app.get("/api/v1/health")
    async def health() -> dict[str, str]:
        return {"service": "predictor", "status": "UP"}

    @app.get("/api/v1/models/{appId}/metrics")
    async def get_model_metrics(appId: str, horizonDays: str = Query("1", pattern="^(1|7)$")):
        return model_evidence(dict(predictions.execution), appId, int(horizonDays))

    @app.get("/api/v1/models/{appId}/review")
    async def review_app_model(appId: str, horizonDays: str = Query("1", pattern="^(1|7)$")):
        if data_source is None:
            raise ImportProblem("MYSQL_REQUIRED", "模型评审必须读取真实 MySQL 观测，当前不是 MySQL 数据源。", 409)
        horizon = int(horizonDays)
        configured = data_source.model_spec(appId, horizon)
        if configured:
            spec_dict = configured["spec"]
            try:
                current_spec = ModelSpec(spec_dict["method"], spec_dict["window"], spec_dict.get("halfLife"))
            except (KeyError, TypeError, ValueError):
                raise ImportProblem("APP_MODEL_SPEC_INVALID", f"{appId}/{horizon} 的 App 参数配置无效。", 503) from None
            source, version = "APP_CONFIG", configured.get("configVersion")
        else:
            current_spec = config_for(appId, horizon)
            source, version = "REVIEWED_CONFIG", None
        days, _start, _end = await asyncio.to_thread(data_source.review_observations, appId, 40)
        try:
            result = await asyncio.to_thread(
                review_model, days, appId, horizon, current_spec,
                current_source=source, app_config_version=version,
            )
        except ValueError as error:
            raise ImportProblem("INSUFFICIENT_REVIEW_WINDOW", f"真实观测不足以形成完整滚动窗口：{error}。", 422) from None
        result["availableModels"] = [{"modelVersion": MODEL_VERSION, "predictionSource": "MODEL"}]
        return result

    @app.post("/api/v1/imports", status_code=202)
    async def submit_import(
        file: UploadFile = File(...), mapping: str = Form(...),
        datasetType: str = Form(...), datasetSource: str = Form(...),
        sourceUnit: str = Form(...), appId: str | None = Form(None),
        idempotency_key: str = Header(..., alias="Idempotency-Key"),
    ):
        try:
            if not idempotency_key.strip() or len(idempotency_key) > 200:
                raise ImportProblem("INVALID_REQUEST", "Idempotency-Key 必须为 1 至 200 字符的非空键。")
            if not file.filename or not file.filename.lower().endswith(".csv"):
                raise ImportProblem("INVALID_CSV", "请选择 .csv 文件。")
            try:
                parsed_mapping = json.loads(mapping)
            except (ValueError, TypeError):
                raise ImportProblem("INVALID_MAPPING", "mapping 必须为合法 JSON 对象。") from None
            config = ImportConfig(parsed_mapping, datasetType, datasetSource, sourceUnit, appId)
            staged = await stage_upload(file, store.incoming, max_file_bytes)
            return await asyncio.to_thread(store.accept_staged, staged, config, idempotency_key, file.filename)
        finally:
            await file.close()

    @app.get("/api/v1/imports/{importTaskId}")
    def get_import(importTaskId: str):
        return store.get_import(importTaskId)

    @app.get("/api/v1/datasets")
    def list_datasets(page: int = Query(1, ge=1), pageSize: int = Query(20, ge=1, le=200)):
        return store.list_datasets(page, pageSize)

    @app.get("/api/v1/datasets/{datasetId}")
    def get_dataset(datasetId: str):
        return store.get_dataset(datasetId)

    @app.get("/api/v1/datasets/{datasetId}/quality")
    def get_quality(datasetId: str):
        return store.get_quality(datasetId)

    @app.get("/api/v1/datasets/{datasetId}/observations")
    def get_observations(datasetId: str, appId: str, startDate: str, endDate: str,
                         granularity: str = 'DAILY', fileKey: str | None = None,
                         topK: int = Query(50, ge=1, le=50)):
        return observations(store, datasetId, appId, startDate, endDate, granularity, fileKey, topK)

    @app.post("/api/v1/predictions", status_code=202)
    def submit_prediction(body: dict = Body(...)):
        return predictions.accept(body)

    @app.get("/api/v1/tasks/{taskId:path}")
    def get_prediction_task(taskId: str):
        return predictions.get_task(taskId)

    @app.get("/api/v1/predictions/{runId}/items")
    def get_prediction_items(runId: str, page: int = Query(1, ge=1), pageSize: int = Query(50, ge=1, le=200)):
        return predictions.get_items(runId, page, pageSize)

    return app


app = create_app()
