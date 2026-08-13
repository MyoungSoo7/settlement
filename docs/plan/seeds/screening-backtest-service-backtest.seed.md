# Seed — screening-backtest-service 백테스트 as-is 사양

> **상태: CONFIRMED** (2026-08-13) · 정본 데이터: [`screening-backtest-service-backtest.seed.yaml`](screening-backtest-service-backtest.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**screening-backtest-service(Python/FastAPI 8120 — investment-service 매매계획 재현·종가 전진 청산 3분류·
포트폴리오 위험/수익 지표 8종)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함                                        | 제외                              |
| ------------------------------------------- | --------------------------------- |
| 매매계획 (3분할·틱 내림·평단/손절/익절)     | 투자점수·주문 집행(investment)    |
| 청산 시뮬레이션 (익절 우선·손절·MTM)        | 시세 수집(market)                 |
| 포트폴리오 지표 8종 + 자산곡선              |                                   |
| API 표면 · pydantic 입력 검증               |                                   |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **3분할** — 30%/30%/40% @ 100%/95%/90%, 손절 ×0.93, 익절 ×1.20 (`trade_plan.py:30-37`).
2. **KRX 틱 내림** — 2023-01 개편 6구간 + 최상단 1,000원, `ROUND_FLOOR` (`:19-57`).
3. **예산 배분** — 구간별 `floor(예산×비중/진입가)`, 총수량 0이면 `feasible=False` + 사유.
4. **청산 순서** — 익절 검사가 손절보다 먼저. 둘 다 없으면 마지막 종가 MTM, 데이터 없으면 진입가 플랫 (`backtest.py:63-77`).
5. **체결가는 계획가** — 익절/손절 시 종가가 아니라 `take`/`stop` 을 기록.
6. **불가능한 픽은 거래가 아니다** — 수익률·지표 모수에서 제외 (`engine.py:88-105`).
7. **MDD 는 음수 분수**, **Sharpe 는 rf=0·모집단 std·비연율**, std=0이면 0.
8. **전부 순수 함수** — `core/` 는 상태·I/O 없이 import 테스트 가능.

## 이벤트 계약

**없음 — 순수 계산 API.** 시세는 요청 본문 또는 번들 샘플에서만 온다.

## 수용 기준 (게이트 매핑)

| AC   | 기준                              | 게이트                       |
| ---- | --------------------------------- | ---------------------------- |
| AC-1 | 틱 내림·3분할·평단/손절/익절 일치 | `tests/test_trade_plan.py`   |
| AC-2 | 청산 3분류·경계·지표 일치         | `tests/test_backtest.py`     |
| AC-3 | API 계약·데모 실행 일치           | `tests/test_api.py`          |
| AC-4 | Python 3.11 pytest GREEN          | `polyglot-ci.yml`            |

## Known Issues (발견만 기록)

- **KI-1 ★high**: Java `TradePlanPolicy` 를 복제했는데 **계약 테스트가 없다** — Java 가 바뀌면 낡은 규칙을 백테스트하고도 초록불. 서비스의 존재 이유를 무력화.
- **KI-2**: `MARKET_BASE_URL` 이 선언만 있고 참조 코드가 없다(사문화된 설정).
- **KI-3**: 종가만 보는 청산이 **구조적 낙관 편향** — 응답에 표시되지 않는다.
- **KI-4**: CAGR 기간이 픽별 보유일 **합** — 동시 보유 시 CAGR 과소.
- **KI-5**: 자산곡선이 순차 전량 배분 가정(주석 명문) — 픽 순서가 MDD 를 바꾼다.
- **KI-6**: Sharpe 가 비연율인데 필드명은 그냥 `sharpe`.
- **KI-7**: 수수료·세금·슬리피지 0 — 최소 4회 체결 대비 괴리.
- **KI-8 ★high**: 호출자·배선·인가 전무 — 로컬 실행/데모 용도로만 존재.
