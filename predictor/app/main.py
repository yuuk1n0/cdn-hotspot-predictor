import asyncio
import json
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, Query, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .csv_import import ImportConfig, ImportProblem
from .import_store import ImportStore


def create_app(data_dir=None, *, max_file_bytes=None, max_rows=None):
    root = Path(data_dir or os.getenv("PREDICTOR_DATA_DIR", Path(__file__).resolve().parents[2] / "runtime" / "predictor"))
    max_file_bytes = max_file_bytes if max_file_bytes is not None else int(os.getenv("IMPORT_MAX_BYTES", 5 * 1024 * 1024))
    max_rows = max_rows if max_rows is not None else int(os.getenv("IMPORT_MAX_ROWS", 20000))
    if max_file_bytes <= 0 or max_rows <= 0:
        raise ValueError("导入文件和行数上限必须大于零。")
    store = ImportStore(root, max_rows)

    @asynccontextmanager
    async def lifespan(app):
        await asyncio.to_thread(store.initialize)
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
        try:
            yield
        finally:
            stop.set()
            await task

    app = FastAPI(title="CDN Hotspot Predictor", version="0.1.0", lifespan=lifespan)
    app.state.imports = store
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
        return JSONResponse(status_code=503, content={"error": {"code": "STORAGE_UNAVAILABLE", "message": "持久存储暂不可用；未确认接收，请使用原幂等键重试。"}})

    @app.get("/api/v1/health")
    async def health() -> dict[str, str]:
        return {"service": "predictor", "status": "UP"}

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
            raw = await file.read(max_file_bytes + 1)
            if len(raw) > max_file_bytes:
                raise ImportProblem("FILE_TOO_LARGE", f"文件超过 {max_file_bytes} B 限制；未截取导入。", 413)
            if not raw:
                raise ImportProblem("INVALID_CSV", "CSV 文件为空。")
            return await asyncio.to_thread(store.accept, raw, config, idempotency_key, file.filename)
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

    return app


app = create_app()
