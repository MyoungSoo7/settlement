"""Entrypoint: ``python -m anomaly_service`` starts the uvicorn server."""
from __future__ import annotations

import uvicorn

from .config import get_settings


def main() -> None:
    settings = get_settings()
    uvicorn.run(
        "anomaly_service.api.app:app",
        host="0.0.0.0",
        port=settings.port,
        log_config=None,  # we do our own JSON logging
    )


if __name__ == "__main__":
    main()
