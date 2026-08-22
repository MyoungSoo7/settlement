"""Demo series generation and loading.

The demo endpoint is the only thing most people ever see of this service, so
the series behind it has to be reproducible: the same seed must give the same
numbers, or two "identical" demo runs disagree and nobody can tell which one
was the bug.
"""
from __future__ import annotations

import csv
from datetime import date

import pytest

from forecast_service import demo_data
from forecast_service.demo_data import (
    SEASON_PERIOD,
    generate_series,
    load_demo_series,
    write_demo_csv,
)


def test_generate_series_is_deterministic_for_a_seed():
    assert generate_series(n=30, seed=42) == generate_series(n=30, seed=42)


def test_a_different_seed_gives_a_different_series():
    assert generate_series(n=30, seed=42) != generate_series(n=30, seed=43)


def test_series_length_and_dates_are_consecutive_days():
    rows = generate_series(n=10, start=date(2025, 1, 1), seed=1)

    assert len(rows) == 10
    assert rows[0][0] == date(2025, 1, 1)
    assert rows[-1][0] == date(2025, 1, 10)
    assert all((b[0] - a[0]).days == 1 for a, b in zip(rows, rows[1:]))


def test_values_are_never_negative():
    # Revenue is floored at 0 — a negative settlement total would be nonsense
    # and would quietly poison MAPE (division by a negative actual).
    assert all(value >= 0.0 for _, value in generate_series(n=200, seed=7))


def test_weekends_dip_which_is_what_makes_the_seasonality_visible():
    rows = generate_series(n=SEASON_PERIOD * 4, seed=42)
    values = [v for _, v in rows]
    weekend = [v for i, v in enumerate(values) if i % SEASON_PERIOD in (5, 6)]
    weekday = [v for i, v in enumerate(values) if i % SEASON_PERIOD not in (5, 6)]

    assert sum(weekend) / len(weekend) < sum(weekday) / len(weekday)


def test_write_demo_csv_materializes_the_series(tmp_path):
    path = write_demo_csv(tmp_path / "nested" / "demo.csv")

    assert path.exists()
    with path.open() as fh:
        rows = list(csv.DictReader(fh))
    assert rows[0].keys() == {"date", "value"}
    assert len(rows) == 120


def test_load_demo_series_reads_the_bundled_csv_when_present(tmp_path, monkeypatch):
    csv_path = tmp_path / "demo.csv"
    csv_path.write_text("date,value\n2025-01-01,100.5\n2025-01-02,200.25\n", encoding="utf-8")
    monkeypatch.setattr(demo_data, "_DEMO_CSV", csv_path)

    assert load_demo_series() == [
        {"date": "2025-01-01", "value": 100.5},
        {"date": "2025-01-02", "value": 200.25},
    ]


def test_load_demo_series_falls_back_to_generating_when_the_csv_is_absent(tmp_path, monkeypatch):
    monkeypatch.setattr(demo_data, "_DEMO_CSV", tmp_path / "missing.csv")

    series = load_demo_series()

    # The demo must work in a container that never ran the build-time step.
    assert len(series) == 120
    assert set(series[0]) == {"date", "value"}


def test_round_trip_csv_matches_the_generated_series(tmp_path, monkeypatch):
    csv_path = tmp_path / "demo.csv"
    write_demo_csv(csv_path)
    monkeypatch.setattr(demo_data, "_DEMO_CSV", csv_path)

    loaded = load_demo_series()
    generated = generate_series()

    assert [row["date"] for row in loaded] == [d.isoformat() for d, _ in generated]
    assert loaded[0]["value"] == pytest.approx(generated[0][1])
