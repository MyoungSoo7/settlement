"""Runtime configuration read from environment variables."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    """Immutable app settings sourced from the environment."""

    port: int = 8122
    host: str = "0.0.0.0"
    log_level: str = "INFO"
    # Minimum number of full seasons required before Holt-Winters seasonality
    # is attempted. Below this we fall back to trend-only or seasonal-naive.
    min_seasons_for_seasonal: int = 2


def load_settings() -> Settings:
    """Build Settings from environment variables (with defaults)."""
    return Settings(
        port=int(os.getenv("PORT", "8122")),
        host=os.getenv("HOST", "0.0.0.0"),
        log_level=os.getenv("LOG_LEVEL", "INFO").upper(),
        min_seasons_for_seasonal=int(os.getenv("MIN_SEASONS_FOR_SEASONAL", "2")),
    )
