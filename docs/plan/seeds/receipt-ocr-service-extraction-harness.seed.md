# Seed — receipt-ocr-service 영수증 추출·채점 하네스 as-is 사양

> **상태: CONFIRMED** (2026-08-22 역산) · 정본 데이터: [`receipt-ocr-service-extraction-harness.seed.yaml`](receipt-ocr-service-extraction-harness.seed.yaml)
> 자매 문서: [`../prd/receipt-ocr-service.md`](../prd/receipt-ocr-service.md) · [`../prd/card-service.md`](../prd/card-service.md)
>
> | 판 | 일자       | 대조 기준             | 비고                                     |
> | -- | ---------- | --------------------- | ---------------------------------------- |
> | v1 | 2026-08-22 | `develop` `92d25c463` | 최초 결정화 (Phase 0·1 완료 시점)        |
>
> **원칙**: 이 Seed 는 "현행 코드가 실제로 하는 일"의 불변 기술이다. 결함은 교정하지 않고
> Known Issues 로만 기록한다.

## Goal (한 줄)

**영수증 필드 추출의 자체 구현과, 그것을 운영 대사 규칙·비대칭 비용으로 채점하는 하네스의 현행
동작을 불변 사양으로 결정화해, 프로바이더 교체 판단의 회귀 기준선 · Phase 2(파인튜닝)·Phase 3(Java
어댑터 교체)의 베이스로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| 합성 골든셋 생성 규칙(시나리오 5 × 촬영조건 7) | 실물 영수증 라벨셋 (미확보) |
| 채점 규칙 — 운영 대사 판정 + 비대칭 비용 | 파인튜닝(Phase 2) |
| 추출 API 계약(`POST /extract` 200/400/413/503) | Java 어댑터 교체(Phase 3) |
| 총액 판정 3근거 · 무폴백 원칙 | 운영 배포·모니터링 (미배선) |
| Java 계약 동형성(VO 검증·프롬프트 원문) | 상호명 판독 품질 (판정에 쓰이지 않음) |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **판정으로 채점한다** — 필드 정확도가 아니라 모델 출력을 **운영과 같은 대사 규칙**에 통과시킨
   뒤 판정끼리 대조한다(`eval/scorer.py` → `domain/matcher.py`, Java `ExpenseReceiptMatcher` 이식본).
2. **오답 비용은 비대칭이다** — 리뷰 유입 1(`scorer.py:28`) · 추출 실패 3(`:30`) · 조기 종결
   10(`:32`) · 오판정 25(`:34`,`:36`). 가중 오류비용이 단일 판정 지표다.
3. **거래일 허용오차 ±1일**(`matcher.py:26`), 기준 시각대는 KST(`:23`) — VAN 매입 시점과 전표
   시점의 하루 차를 흡수한다.
4. **신뢰도 미달이 최우선 분기** — 임계 미만이면 값 일치와 무관하게 `NEEDS_REVIEW`
   (`matcher.decide`, `matcher.py:45` 이하 첫 분기).
5. **지어내지 않는다** — 총액을 못 읽으면 부분 결과를 만들지 않고 `ParseFailed`
   (`providers/parsing.py:106`,`:205`) → API 는 **503**(`api/app.py:53`). 거래일은 다르다:
   못 읽으면 `null` 로 내보내고 대사 규칙이 `NEEDS_REVIEW` 로 흘린다(설계된 안전 경로).
6. **총액 판정 3근거** — ① 구조 검증(`공급가액 + 부가세 = 합계`)이 서면 그 합이 총액이고 OCR 점수와
   **독립인 근거**라 신뢰도 가산 `+0.15`(`parsing.py:49`,`:148`) ② 실패 시 글자 크기 추측, 이건
   추측이므로 벌점 `−0.25`(`:58`,`:151`) ③ 둘 다 실패면 끊는다.
7. **금액은 문자열로 직렬화한다** — JSON number 로 보내면 받는 쪽에서 float 이 되어 원 단위가
   흔들린다(`api/app.py` 응답 구성). 내부 표현은 `Decimal`(부동소수 금지 — 저장소 가드레일 동형).
8. **Java VO 와 같은 것을 거부한다** — 총액 0/음수·신뢰도 범위 밖은 여기서도 거부한다
   (`domain/extracted.py`). 여기서 통과한 추출이 운영에서 예외로 떨어지면 평가 숫자가 거짓말이 된다.
9. **업로드 계약** — 이미지는 요청 본문 그대로(multipart 아님), 상한 12MB(`api/app.py:26`),
   초과 413(`:45`), 빈 본문 400(`:42`), 엔진 붕괴도 같은 503 계약(`:56`).
10. **필드별 신뢰도를 먼저 내보낸다** — `fieldConfidence.{amount,date}` 는 아직 Java 포트가 받지
    못하지만 서버는 싣는다. baseline 의 치명 오류가 정확히 그 부재에서 났기 때문이다.
11. **골든셋은 시드 고정** — `build --seed`(기본 `20260821`)로 재현 가능해야 한다.
12. **합성 점수는 절대 성능이 아니다** — 모델 간 상대 비교와 하네스 검증 전용.

## 골든셋 구성 (`data/goldenset.json`, schema_version 1, 35건)

시나리오 5종 × 촬영조건 7종 교차. 3건에 1건은 할인·포인트가 붙어 금액 후보가 여러 개다.

| 시나리오 | 정답 판정 |
|---|---|
| `CLEAN` | `MATCHED` |
| `NEXT_DAY`(심야 결제로 전표 +1일) | `MATCHED` — ±1일이 흡수 |
| `NO_DATE` | `NEEDS_REVIEW` |
| `AMOUNT_TAMPERED` | `MISMATCHED` |
| `STALE_DATE` | `MISMATCHED` |

촬영조건: `PRISTINE`·`FADED`·`CRUMPLED`·`SKEWED`·`LOW_LIGHT`·`LOW_RES`·`GLARE` (각 5건).

## 실측 기준선 (2026-08-21, 35건, 임계 0.80)

| 지표 | gemini-2.5-flash | local+prep |
|---|---|---|
| 대사 판정 일치율 | 88.6% | 42.9% |
| 치명 — 오종결 / 부정 통과 | 1 / 0 | 1 / 0 |
| 총액 정확 일치 | 100% | 79.4% |
| 리뷰 큐 유입률 | 17.1% | 62.9% |
| 지연 p50 | 5289ms | 1937ms |
| 외부 전송 / 건당 비용 | 있음 | 없음 / 0 |

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 대사 판정 규칙이 Java 매처와 동형 | `pytest tests/test_matcher.py` (13케이스) |
| AC-2 | 비대칭 비용·ECE·백분위 채점이 정의대로 | `tests/test_scorer.py` (22) |
| AC-3 | 총액 3근거·날짜 파싱이 실제 OCR 출력 형태를 견딘다 | `tests/test_parsing.py` (27) |
| AC-4 | API 계약 200/400/413/503, 무폴백 | `tests/test_api.py` (10) |
| AC-5 | baseline 프롬프트가 Java 원문과 문자 단위 일치 | `tests/test_gemini_provider.py` (22) |
| AC-6 | 골든셋 생성·렌더가 시드로 재현된다 | `tests/test_generator.py`(15) · `test_renderer.py`(12) · `test_goldenset.py`(9) |
| AC-7 | 테스트가 정책 훼손을 **실제로 탐지한다** | `python tools/mutation_check.py` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1**: **아무도 이 서비스를 부르지 않는다.** compose·gateway·CI·Dockerfile 어디에도 없고
  card-service 의 `ExtractReceiptFieldsPort` 구현은 여전히 `GeminiReceiptOcrAdapter` 하나다.
  폴리글랏 7종은 `polyglot-ci.yml` 경로 필터에 있는데 이 서비스만 없어 **CI 가 코드를 한 번도
  돌리지 않는다.** PRD G-1 / T-1.
- **KI-2**: 정확도 격차가 크다(판정 일치율 42.9% vs 88.6%). 격차 대부분이 리뷰 큐 범람에서 온다 —
  OCR 토큰 점수는 "이 글자를 이렇게 읽었다"는 확신이지 "이 값이 총액이다"라는 확신이 아니라
  운영 임계 0.80 과 스케일이 맞지 않는다. Phase 2 의 과제.
- **KI-3**: `fieldConfidence` 를 받을 곳이 없다 — Java `ExtractedReceipt` 는 스칼라 `confidence`
  하나만 받는다. 지금 교체해도 baseline 의 치명 오류 패턴이 Java 경계에서 재현된다.
- **KI-4**: 골든셋이 합성 35건이다. **35건에서 "치명 1건"은 0건과 2건을 통계적으로 구분하지 못한다.**
- **KI-5**: CLI 기본 생성 수량은 40 인데 추적된 골든셋은 35건이다(시나리오 5 × 조건 7의 정확한
  교차). 기본값으로 재생성하면 골든셋이 조용히 바뀌어 과거 리포트와 비교 불가가 된다 —
  저장본을 만든 인자 조합이 코드에도 데이터에도 기록돼 있지 않다.
- **KI-6**: 운영 임계값(0.80)이 card-service 설정에서 온 값인데 양쪽을 묶는 드리프트 가드가 없다.
  프롬프트에는 가드(AC-5)가 있는데 임계값에는 없다.
