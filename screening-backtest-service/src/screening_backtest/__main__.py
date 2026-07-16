"""Entrypoint: `python -m screening_backtest` starts uvicorn on $PORT."""

from __future__ import annotations

import uvicorn

from .config import settings


def main() -> None:
    uvicorn.run(
        "screening_backtest.api.app:app",
        host="0.0.0.0",
        port=settings.port,
        log_config=None,
    )


if __name__ == "__main__":
    main()
