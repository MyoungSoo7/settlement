"""Runtime configuration (env-driven) and structured logging setup."""

from __future__ import annotations

import json
import logging
import os
import sys
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    port: int
    log_level: str
    market_base_url: str

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            port=int(os.environ.get("PORT", "8120")),
            log_level=os.environ.get("LOG_LEVEL", "INFO").upper(),
            market_base_url=os.environ.get("MARKET_BASE_URL", "http://market-service:8080"),
        )


class JsonFormatter(logging.Formatter):
    """Minimal structured (JSON line) log formatter."""

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        for key, value in getattr(record, "extra_fields", {}).items():
            payload[key] = value
        return json.dumps(payload, ensure_ascii=False)


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(getattr(logging, level, logging.INFO))


settings = Settings.from_env()
