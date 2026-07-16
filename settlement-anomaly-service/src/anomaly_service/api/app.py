"""FastAPI application wiring for settlement-anomaly-service."""
from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI

from ..config import get_settings
from ..logging_config import configure_logging, get_logger
from ..schemas import (
    ScoreRequest,
    ScoreResponse,
    TrainRequest,
    TrainResponse,
)
from ..service import AnomalyService

log = get_logger("anomaly.api")


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging(settings.log_level)
    service = AnomalyService(settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # Fit-on-startup so /score works immediately.
        service.ensure_trained()
        log.info("startup complete; model ready")
        yield

    app = FastAPI(
        title="settlement-anomaly-service",
        description="Anomaly / fraud scoring on settlement & payout records.",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.state.service = service

    @app.get("/health")
    def health() -> dict:
        return {"status": "UP"}

    @app.post("/train", response_model=TrainResponse)
    def train(req: TrainRequest | None = None) -> TrainResponse:
        records = None
        if req is not None and req.records is not None:
            records = [r.model_dump() for r in req.records]
        result = service.train(records)
        return TrainResponse(**result)

    @app.post("/score", response_model=ScoreResponse)
    def score(req: ScoreRequest) -> ScoreResponse:
        records = [r.model_dump() for r in req.records]
        results = service.score(records)
        return ScoreResponse(
            threshold=settings.anomaly_threshold,
            results=results,
        )

    @app.get("/score/demo")
    def score_demo() -> dict:
        return service.demo()

    return app


app = create_app()
