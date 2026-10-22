import asyncio
import hashlib

import pytest

from app.csv_import import ImportConfig, ImportProblem
from app.import_store import ImportStore
from app.upload_stream import StagedUpload, stage_upload
from test_csv_import import MAPPING


class RecordingUpload:
    def __init__(self, content: bytes):
        self.content = content
        self.offset = 0
        self.requested_sizes = []

    async def read(self, size: int) -> bytes:
        self.requested_sizes.append(size)
        chunk = self.content[self.offset:self.offset + size]
        self.offset += len(chunk)
        return chunk


def test_stage_upload_reads_bounded_chunks_and_preserves_exact_bytes(tmp_path):
    content = b"x" * (3 * 1024 * 1024 + 17)
    upload = RecordingUpload(content)

    staged = asyncio.run(stage_upload(upload, tmp_path, 4 * 1024 * 1024))

    assert max(upload.requested_sizes) == 1024 * 1024
    assert staged.size == len(content)
    assert staged.sha256 == hashlib.sha256(content).hexdigest()
    assert staged.path.read_bytes() == content


@pytest.mark.parametrize(
    ("content", "max_bytes", "code", "status"),
    [
        (b"", 8, "INVALID_CSV", 422),
        (b"123456789", 8, "FILE_TOO_LARGE", 413),
    ],
)
def test_stage_upload_rejects_invalid_size_without_leaving_partial_files(
    tmp_path, content, max_bytes, code, status
):
    with pytest.raises(ImportProblem) as raised:
        asyncio.run(stage_upload(RecordingUpload(content), tmp_path, max_bytes))

    assert raised.value.code == code
    assert raised.value.status == status
    assert list(tmp_path.iterdir()) == []


def test_accept_staged_moves_bytes_and_cleans_idempotent_retries(tmp_path):
    store = ImportStore(tmp_path)
    store.initialize()
    config = ImportConfig(MAPPING, "FILE_DAILY", "REAL", "B")
    content = b"file,date,bytes,app\n"

    first_path = store.incoming / "first.part"
    first_path.write_bytes(content)
    first = StagedUpload(first_path, len(content), hashlib.sha256(content).hexdigest())
    receipt = store.accept_staged(first, config, "same-key", "first.csv")

    original = tmp_path / "imports" / receipt["importTaskId"] / "original.csv"
    assert receipt["status"] == "PENDING"
    assert not first_path.exists()
    assert original.read_bytes() == content

    retry_path = store.incoming / "retry.part"
    retry_path.write_bytes(content)
    retry = StagedUpload(retry_path, len(content), first.sha256)
    retried = store.accept_staged(retry, config, "same-key", "renamed.csv")

    assert retried == receipt
    assert not retry_path.exists()
    assert original.read_bytes() == content


def test_accept_staged_cleans_conflicting_retry(tmp_path):
    store = ImportStore(tmp_path)
    store.initialize()
    config = ImportConfig(MAPPING, "FILE_DAILY", "REAL", "B")
    original = b"first"
    changed = b"changed"

    first_path = store.incoming / "first.part"
    first_path.write_bytes(original)
    store.accept_staged(
        StagedUpload(first_path, len(original), hashlib.sha256(original).hexdigest()),
        config,
        "same-key",
        "first.csv",
    )

    conflict_path = store.incoming / "conflict.part"
    conflict_path.write_bytes(changed)
    with pytest.raises(ImportProblem) as raised:
        store.accept_staged(
            StagedUpload(conflict_path, len(changed), hashlib.sha256(changed).hexdigest()),
            config,
            "same-key",
            "changed.csv",
        )

    assert raised.value.code == "IDEMPOTENCY_CONFLICT"
    assert not conflict_path.exists()


def test_initialize_removes_incomplete_parts_but_preserves_other_incoming_files(tmp_path):
    incoming = tmp_path / "incoming"
    incoming.mkdir()
    incomplete = incoming / ".upload-interrupted.part"
    unrelated = incoming / "keep.txt"
    incomplete.write_bytes(b"partial")
    unrelated.write_bytes(b"keep")

    ImportStore(tmp_path).initialize()

    assert not incomplete.exists()
    assert unrelated.read_bytes() == b"keep"

