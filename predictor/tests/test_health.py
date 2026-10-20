import asyncio

from httpx import ASGITransport, AsyncClient

from app.main import app


def test_health_endpoint_reports_predictor_is_up() -> None:
    async def request_health():
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get("/api/v1/health")

    response = asyncio.run(request_health())

    assert response.status_code == 200
    assert response.json() == {"service": "predictor", "status": "UP"}
