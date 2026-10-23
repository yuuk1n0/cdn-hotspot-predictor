"""Adapt complete prediction results to the service page contract."""

from typing import Mapping

def paginate_result(run: Mapping, page: int, page_size: int) -> dict:
    """Adapt a saved full result to the existing 1-based HTTP page contract."""
    if page < 1 or not 1 <= page_size <= 200:
        raise ValueError("page must be positive and pageSize must be 1..200")
    start = (page - 1) * page_size
    result = {key: value for key, value in run.items() if key != "items"}
    result.update(page=page, pageSize=page_size, items=run["items"][start:start + page_size])
    return result
