"""Bounded-memory upload staging for CSV import receipts."""

import hashlib
import os
import uuid
from dataclasses import dataclass
from pathlib import Path

from .csv_import import ImportProblem


@dataclass(frozen=True)
class StagedUpload:
    path: Path
    size: int
    sha256: str


def _sync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


async def stage_upload(
    file,
    incoming_dir: Path,
    max_bytes: int,
    chunk_bytes: int = 1024 * 1024,
) -> StagedUpload:
    if max_bytes <= 0 or chunk_bytes <= 0:
        raise ValueError("上传字节上限和分块大小必须大于零。")
    incoming_dir = Path(incoming_dir)
    incoming_dir.mkdir(parents=True, exist_ok=True)
    path = incoming_dir / f".upload-{uuid.uuid4().hex}.part"
    digest = hashlib.sha256()
    size = 0
    try:
        with path.open("xb") as stream:
            while True:
                chunk = await file.read(chunk_bytes)
                if not chunk:
                    break
                size += len(chunk)
                if size > max_bytes:
                    raise ImportProblem(
                        "FILE_TOO_LARGE",
                        f"文件超过 {max_bytes} B 限制；未截取导入。",
                        413,
                    )
                stream.write(chunk)
                digest.update(chunk)
            if size == 0:
                raise ImportProblem("INVALID_CSV", "CSV 文件为空。")
            stream.flush()
            os.fsync(stream.fileno())
        _sync_directory(incoming_dir)
        return StagedUpload(path=path, size=size, sha256=digest.hexdigest())
    except BaseException:
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        raise

