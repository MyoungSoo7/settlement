"""Uvicorn entrypoint: `python -m forecast_service.main`."""
from __future__ import annotations

import uvicorn

from .config import load_settings


def main() -> None:
    settings = load_settings()
    uvicorn.run(
        "forecast_service.app:app",
        host=settings.host,
        port=settings.port,
        log_config=None,  # we install our own JSON logging
    )


if __name__ == "__main__":
    main()
