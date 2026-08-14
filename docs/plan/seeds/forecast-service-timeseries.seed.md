# Seed — forecast-service 시계열 예측 as-is 사양

> **상태: CONFIRMED** (2026-08-13) · 정본 데이터: [`forecast-service-timeseries.seed.yaml`](forecast-service-timeseries.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**forecast-service(Python/FastAPI 8122 — Holt-Winters + seasonal-naive 폴백·잔차 기반 ±1.96σ 구간·
표본내 MAPE/RMSE·관측 간격 추론)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함                                  | 제외                              |
| ------------------------------------- | --------------------------------- |
| 모델 2종과 폴백 체인                  | 정산 실적 산출(settlement)        |
| 예측 구간·지표(MAPE 0 제외 규칙)      | 화면 렌더링(프론트)               |
| 날짜 처리(오름차순 강제·간격 추론)    |                                   |
| API 표면 · JSON 안전 치환             |                                   |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **계절 HW 는 조건부** — `m>=2` 이고 `n >= max(min_seasons·m+1, 2m+1)` 일 때만 `seasonal='add'` (`models.py:100-106`).
2. **폴백 체인** — `n<4` → seasonal-naive(로그), 적합 예외 → seasonal-naive(warn).
3. **모델 정체를 숨기지 않는다** — 응답 라벨 `holt_winters_seasonal` / `holt_winters_trend` / `seasonal_naive`.
4. **구간은 ±1.96σ** — 잔차 std 가 비유한/음수면 0. **전 horizon 동일 폭**.
5. **MAPE 는 실측 0 제외**, 전부 0이면 NaN (`metrics.py:37-40`).
6. **정렬은 호출자 책임** — 오름차순이 아니면 422, 대신 고쳐주지 않는다.
7. **간격은 관측이 정한다** — `dates[-1]-dates[-2]`, 0 이하면 1일.
8. **응답은 항상 파싱 가능** — NaN/Inf → 0.0 (`app.py:25-27`).
9. **설정은 호출 시마다 읽는다** — `load_settings()` 가 매번 `os.getenv`(대조: anomaly-service 는 import 시점 고정).

## 이벤트 계약

**없음 — 순수 계산 API.** 시계열은 요청 본문 또는 번들 데모 CSV 에서만 온다.

## 수용 기준 (게이트 매핑)

| AC   | 기준                                  | 게이트                    |
| ---- | ------------------------------------- | ------------------------- |
| AC-1 | MAPE 0 제외·RMSE·에러 조건 일치       | `tests/test_metrics.py`   |
| AC-2 | 모델 선택·폴백·naive 반복 규칙 일치   | `tests/test_models.py`    |
| AC-3 | 간격 추론·미래 날짜·지표·backtest 일치 | `tests/test_service.py`  |
| AC-4 | API 계약(422·데모 shape) 일치         | `tests/test_api.py`       |
| AC-5 | Python 3.11 pytest GREEN              | `polyglot-ci.yml`         |

## Known Issues (발견만 기록)

- **KI-1 ★high**: 예측 구간이 **기간에 따라 넓어지지 않는다** — 1일 뒤와 14일 뒤가 같은 폭. 장기 신뢰도 과대평가.
- **KI-2**: NaN → 0.0 치환이 실패를 성공처럼 보이게 한다 — `mape: 0.0` 이 "완벽한 예측"으로 읽힌다.
- **KI-3**: `backtest_split`(표본 밖 검증)이 구현돼 있으나 **어떤 엔드포인트도 호출하지 않는다**.
- **KI-4**: 간격을 마지막 두 점으로만 추론 — 불규칙 데이터에서 조용히 틀린 날짜.
- **KI-5**: 실데이터 미연동 — 데모 정확도는 "자기가 만든 패턴을 맞춘 것".
- **KI-6**: 계절 폴백이 조용하다(사유 로그 없음).
- **KI-7 ★high**: 호출자·배선·인가 전무 — CEO 대시보드 배선이 존재하지 않는다.
- **KI-8**: `/metrics`·카운터 없음.
