# Seed — settlement-anomaly-service 이상탐지 스코어링 as-is 사양

> **상태: CONFIRMED** (2026-08-13) · 정본 데이터: [`settlement-anomaly-service-scoring.seed.yaml`](settlement-anomaly-service-scoring.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**settlement-anomaly-service(Python/FastAPI 8121 — 가맹점 MAD robust z + IsolationForest 앙상블·읽히는 근거·
고정 시드 재현성·기동 시 cold-start)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함                                     | 제외                                |
| ---------------------------------------- | ----------------------------------- |
| 피처 6종 (순수·순서보존·폴백)            | 정산 금액 산정(settlement)          |
| 통계 계층 (MAD z·오프아워·배수)          | 인시던트·알림(operation)            |
| ML 계층 (IsolationForest·정규화·보정)    |                                     |
| 앙상블·임계·근거 · API/수명주기          |                                     |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **가맹점 자기 기준선** — median·MAD(×1.4826). `count>=3` 이고 `mad>0` 일 때만 신뢰, 아니면 전역 폴백(근거에 `merchant`/`global` 표기) (`detector.py:130-137`).
2. **z 램프** — 3.5부터 기여, 6.0에서 포화. 오프아워(00~04시) 0.4, 평균 5배↑ 는 `(ratio-5)/20+0.3`. 세 성분 max, 2개↑ 발화 시 +0.15.
3. **앙상블** — `0.55·stat + 0.45·iforest`, 양측 0.5↑ 동의 시 +0.1, 임계 0.7 (`:207-213`).
4. **ML 보정** — `predict == -1` 이면 `if_anomaly` 를 최소 0.6 으로 끌어올리고 근거 추가.
5. **재현성** — seed 42, `n_jobs=1`, 순수 피처 함수.
6. **배치를 죽이지 않는다** — 파싱 불가 타임스탬프는 0시로 강등 (`features.py:36-54`).
7. **cold-start** — lifespan 에서 번들 샘플 학습 → 기동 직후 `/score` 동작.
8. **가드** — 빈 학습셋 `ValueError`, 미적합 스코어링 `RuntimeError`, 임계 초과인데 근거 없으면 기본 근거 삽입.

## 이벤트 계약

**없음 — 순수 HTTP 스코어러.** 호출자가 레코드를 push 한다.

## 수용 기준 (게이트 매핑)

| AC   | 기준                                | 게이트                    |
| ---- | ----------------------------------- | ------------------------- |
| AC-1 | 피처·타임스탬프 폴백·MAD 스케일 일치 | `tests/test_features.py`  |
| AC-2 | 계층·앙상블·임계 판정 일치          | `tests/test_detector.py`  |
| AC-3 | API 계약·데모 이상치 탐지 일치      | `tests/test_api.py`       |
| AC-4 | Python 3.11 pytest GREEN            | `polyglot-ci.yml`         |

## Known Issues (발견만 기록)

- **KI-1**: 환경변수가 프로세스당 1회만 읽힌다 — `Settings` 필드 기본값이 클래스 정의 시점에 굳는다. 독스트링의 "monkeypatch 가능" 주장이 사실이 아니다(대조: forecast-service 는 정상).
- **KI-2 ★high**: `merchant_freq` 가 **배치 크기에 좌우**된다 — 학습 60 vs 추론 1~3. 피처 드리프트.
- **KI-3 ★high**: 합성 데이터로만 학습 — 실 정산 건은 전역 폴백으로만 동작해 "절대 임계값 미사용" 목표가 무력화.
- **KI-4**: `/train` 무인증 전역 모델 교체.
- **KI-5**: 워커·레플리카마다 학습 상태 상이 — 같은 레코드가 다른 점수를 받는다.
- **KI-6**: IF 점수가 학습 범위 밖에서 1.0 포화.
- **KI-7 ★high**: 호출자 없음(compose·게이트웨이·Java 호출 모두 부재) — 실 트래픽 0.
- **KI-8**: `/metrics`·카운터 없음.
