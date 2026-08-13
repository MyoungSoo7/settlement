# ADR 0036 — 증빙 OCR 플랫폼화 (도메인 중립 비전 추출 클라이언트 + 법인카드 영수증 3자 대사)

- 상태: **Accepted** — 2026-08-13
- 관련: ADR 0021(shared-common 플랫폼 라이브러리), ADR 0029(settlement 세무 제출물 — 세금계산서 스캔 OCR),
  `card-service-rules`·`money-safety` 스킬, `tax` 도메인 스캔 대사(`TaxInvoiceScanMatcher`)
- 배경: AI OCR 능력이 settlement `tax` 도메인 안에 갇혀 있다. 같은 능력이 필요한 card-service
  지출관리에는 영수증 **첨부 자체가 없다**(`receiptUrl` 문자열만 보관). 본 ADR 은 OCR 호출 기제를
  도메인 중립 계층으로 승격하고, 첫 확산 대상으로 법인카드 지출관리에 영수증 3자 대사를 붙인다.

## 컨텍스트

### 현행 (2026-08-13 확인)

| 항목 | 상태 |
|---|---|
| OCR 호출 | `settlement-service` `tax/adapter/out/llm/GeminiTaxInvoiceOcrAdapter` — Gemini 비전 `inline_data` + `responseMimeType=application/json`, 봉투 파싱·코드펜스 제거까지 자체 구현 |
| OCR 원칙 | **폴백 없음**(실패=503, 부분 결과를 지어내지 않음), 신뢰도 미달은 리뷰 큐 |
| card 지출관리 | `ExpenseReport.submit(receiptUrl, ...)` — 영수증은 **검증 없는 URL 문자열**. 업로드·저장·판독·대사 없음 |
| card 승인 | `ExpenseWorkflowService.approve` — 영수증 유무·정합과 무관하게 SUBMITTED → APPROVED |

### 결함 1 — 증빙 없는 지출 승인

지출보고서는 매입(capture) 이벤트로 자동 생성되고 금액은 VAN 매입값을 신뢰하지만, **임직원이 첨부하는
증빙(영수증)은 아무것도 검증되지 않는다**. `receiptUrl` 에 아무 문자열을 넣어도 제출·승인이 통과한다.
경비 SaaS 를 표방하면서(카드-rules §지출관리) 증빙 대사가 없는 것은 기능 공백이다.

### 결함 2 — OCR 기제가 도메인에 갇혀 재구현을 부른다

Gemini generateContent 호출·응답 봉투 해체·코드펜스 제거·무폴백 원칙은 **문서 종류와 무관한 기반 기제**인데
tax 어댑터 안에 있다. card 영수증에 OCR 을 붙이려면 같은 코드를 복붙해야 하고, 봉투 파싱 버그 픽스가
서비스마다 갈라진다 — shared-common 이 존재하는 이유(ADR 0021) 그 자체.

## 결정

### 1. shared-common `common.ocr` — 도메인 중립 비전 추출 클라이언트

```
shared-common/src/main/java/github/lms/lemuel/common/ocr/
├── VisionExtractionClient.java    # Gemini generateContent 호출 + 봉투 해체 + 코드펜스 제거 → 내부 JSON 텍스트
└── VisionExtractionException.java # 호출 실패·빈 응답·형식 파손 (unchecked)
```

- **프롬프트·필드 스키마·금액/날짜 해석은 각 도메인 소유** — 클라이언트는 "이미지+프롬프트 → JSON 텍스트"만 한다.
  세금계산서/영수증/담보서류가 무엇을 어떻게 읽을지는 도메인 지식이고 shared 로 올리지 않는다.
- **스프링 빈이 아니다**(`GhostscriptService` 와 같은 결). 각 서비스 어댑터가 자기 프로퍼티(키·모델·baseUrl)로
  생성한다 — 제한 스캔 서비스의 `@Import` 문제를 원천 회피하고, 키·비용 통제를 서비스별로 유지한다.
- **무폴백 원칙 승계**: 실패는 전부 `VisionExtractionException` — 호출 도메인이 자기 503 예외로 번역한다.
  기본값·캐시·추정 응답 금지(재원 폴백 금지와 같은 논리 — 추정 판독을 회계 근거로 쓰는 순간 조용한 오대사다).

### 2. tax 어댑터 재배선 (동작 불변)

`GeminiTaxInvoiceOcrAdapter` 는 HTTP·봉투 처리를 `VisionExtractionClient` 에 위임하고
**프롬프트 + 세금계산서 필드 해석(금액·작성일자·신뢰도)만 남긴다**. 예외는 기존대로
`TaxOcrUnavailableException`(503) 으로 번역 — 기존 테스트·API 계약 전부 불변.

### 3. card-service 영수증 도메인 — 매입↔영수증↔지출보고서 3자 대사

```
card/domain/  ExpenseReceipt(애그리거트) · ExpenseReceiptStatus · ExtractedReceipt(VO) · ExpenseReceiptMatcher(순수 판정)
card/adapter/out/llm/  GeminiReceiptOcrAdapter (+ ReceiptOcrProperties, app.card.receipt-ocr.*)
card/adapter/out/persistence/  expense_receipts (V10, 파일 본문 bytea — company 문서함 선례)
card/adapter/in/web/  POST /internal/api/v1/expense-reports/{reportId}/receipts (multipart)
```

- **상태머신**: `EXTRACTED → MATCHED | MISMATCHED | NEEDS_REVIEW`, `NEEDS_REVIEW → MATCHED | MISMATCHED`(관리자 리뷰).
  MATCHED/MISMATCHED 는 종결 — 번복은 새 영수증 첨부로만.
- **대사 규칙**(`ExpenseReceiptMatcher`, 포트·DB 없는 순수 도메인 — `TaxInvoiceScanMatcher` 와 같은 결):
  - 총액: OCR `totalAmount` vs `CardCapture.capturedAmount` — `compareTo` **정확 일치**(불일치 즉시 MISMATCHED).
  - 거래일: OCR 거래일 vs `capturedAt`(KST 환산) — **±1일 허용**(VAN 매입 시점과 전표 시점의 하루 차 흡수).
  - 상호명: **참고 정보만**(대사 노트에 기록, 판정에 불사용) — OCR 상호 표기는 등록상호와 상시 불일치한다.
  - 신뢰도: `< 0.80`(기본, `app.card.receipt-ocr.review-threshold`) 이면 값 일치와 무관하게 NEEDS_REVIEW.
- **멱등**: `(report_id, file_hash)` UNIQUE — 같은 파일 재업로드는 기존 행 반환, OCR 재호출 없음(tax 와 동일한 비용 방어).
- **소유권**: 업로더 userId 는 보고서 `holderUserId` 와 대조(불일치 403 결). 내부망 경로(`/internal/**`) 관례 유지.

### 4. 승인 게이트 — 대사 미통과 영수증으로는 승인 불가

`ExpenseWorkflowService.approve` 에 게이트를 추가한다:

- 보고서에 영수증이 **있으면** 최신 영수증이 MATCHED 여야 승인 통과. MISMATCHED·NEEDS_REVIEW·EXTRACTED 는 거절.
- 영수증이 **없으면** 기존 경로 그대로 통과 — 점진 도입. 기존 `receiptUrl` 문자열 경로를 즉시 깨지 않는다.
  (전면 강제는 조직별 정책 플래그가 필요한 별도 결정으로 미룬다.)

### 5. 범위 밖

- **Kafka 신규 토픽 없음** — 대사는 card-service 내부에서 닫힌다(계약 작업·컨슈머 없음).
- 예산 초과 승인 차단(카드-rules 기존 결정 유지), insurance 청약·loan 담보 확산(후속 — 본 ADR 의
  클라이언트를 그대로 쓰면 각 서비스 어댑터 1개 + 도메인 판정만 추가하면 된다).

## 결과

- OCR 기반 기제는 shared-common 한 곳: 봉투 파싱 수정·모델 교체가 전 서비스에 한 번에 반영.
- 지출관리가 "URL 문자열 보관"에서 "증빙 판독·대사·승인 게이트"로 — 경비 SaaS 의 핵심 공백 해소.
- 신뢰도 미달·불일치가 사람 리뷰로 흐르는 구조는 tax 와 동형 — 운영 학습 비용 공유.
- 트레이드오프: shared-common 버전 승격 없이 composite build 로 즉시 반영되므로, 클라이언트 시그니처
  변경은 tax·card 양쪽 컴파일에 동시에 걸린다(의도된 결합 — 계약 드리프트를 빌드가 잡는다).
