"""Service-level orchestration: holds the in-memory model and demo dataset.

Kept separate from the FastAPI wiring so the training/scoring flow is testable
without spinning up an HTTP server.
"""
from __future__ import annotations

from typing import Dict, List, Optional

from .config import Settings, get_settings
from .logging_config import get_logger
from .model.detector import EnsembleDetector
from .sample_data import build_sample_dataset

log = get_logger("anomaly.service")


class AnomalyService:
    def __init__(self, settings: Optional[Settings] = None) -> None:
        self.settings = settings or get_settings()
        self.detector: Optional[EnsembleDetector] = None
        self._sample = build_sample_dataset(seed=self.settings.seed)

    # -------------------------------------------------------------- training
    def train(self, records: Optional[List[dict]] = None) -> Dict:
        """Fit the ensemble. Uses bundled sample if no records supplied."""
        data = records if records else self._sample["train"]
        detector = EnsembleDetector(
            seed=self.settings.seed,
            contamination=self.settings.contamination,
            n_estimators=self.settings.n_estimators,
            threshold=self.settings.anomaly_threshold,
        )
        detector.fit(data)
        self.detector = detector
        merchants = len(detector.state.baselines)
        log.info(
            "trained",
            extra={
                "extra": {
                    "trainedRecords": len(data),
                    "merchants": merchants,
                    "usedBundledSample": records is None,
                }
            },
        )
        return {
            "status": "trained",
            "trainedRecords": len(data),
            "merchants": merchants,
            "threshold": self.settings.anomaly_threshold,
            "seed": self.settings.seed,
        }

    def ensure_trained(self) -> None:
        if self.detector is None:
            log.info("cold-start training on bundled sample")
            self.train(None)

    # --------------------------------------------------------------- scoring
    def score(self, records: List[dict]) -> List[dict]:
        self.ensure_trained()
        assert self.detector is not None
        return self.detector.score(records)

    def demo(self) -> Dict:
        """Run the bundled demo dataset end-to-end."""
        self.ensure_trained()
        assert self.detector is not None
        records = self._sample["demo"]
        results = self.detector.score(records)
        flagged = [r["id"] for r in results if r["isAnomaly"]]
        return {
            "threshold": self.settings.anomaly_threshold,
            "expectedOutliers": self._sample["outlier_ids"],
            "flagged": flagged,
            "results": results,
        }
