# 증빙 OCR 플랫폼 구축 진행 기록 (ADR 0036)

> 2026-08-13 ~ 08-14 세션 작업 전체 요약. 설계 정본은 [`adr/0036-receipt-ocr-platform.md`](adr/0036-receipt-ocr-platform.md),
> 서비스별 기능 서술은 [`../SPEC.md`](../SPEC.md) 각 절.

## 한 줄 요약

settlement `tax` 도메인에 갇혀 있던 AI OCR 능력을 shared-common 으로 승격하고, **card(영수증) →
insurance(청약서류) → loan(담보서류) → deposit(예치금 증빙)** 4개 도메인에 동형 확산한 뒤,
통합 리뷰 큐 화면과 전면 강제 정책 플래그까지 얹었다. 전 구간 게이트(JaCoCo LINE 90% + IT) GREEN,
develop 반영 완료.

## 왜 이 작업이었나 (선정 근거)

세 후보 축(AI OCR / settlement 고도화 / Kafka 고도화)을 문서·코드 실측으로 대조한 결과:

- **Kafka**: DLT 일원화·lag 관제·가드 규칙이 이미 완비 — 잔여가 얇았다.
- **settlement**: 정합성 Phase C·FEP REF_ID·월마감이 전부 완료 상태(문서 드리프트였음).
- **AI OCR**: tax 에 프로덕션 수준 파이프라인(Gemini 비전·무폴백 503·신뢰도 리뷰 큐)이 있는데
  card 지출관리에는 영수증 **첨부 자체가 없었다**(`receiptUrl` 문자열뿐) — 가장 큰 미개척지.

부수 확인: 승인경로 인가 누락 의심(T-1)은 이미 `SecurityConfig` + `AdminPathAuthorizationTest` 로
닫혀 있었다(PRD 가 낡았던 것).

## 공통 구조 (5개 도메인 동형)

```
멀티파트 업로드(bytea 보관, company 문서함 선례)
  → shared-common VisionExtractionClient(Gemini generateContent, 무폴백 503)
  → 도메인 매처 자동 대사 (핵심 금액 compareTo 정확 일치 · 날짜 허용폭 · 신뢰도 <0.80 → NEEDS_REVIEW)
  → (앵커, file_hash) UNIQUE 멱등 — 재업로드 시 OCR 재호출 없음 (비용 방어)
  → 승인/기표 게이트: 첨부돼 있으면 최신 증빙 MATCHED 필수(422), 무첨부는 점진 도입
  → NEEDS_REVIEW 는 운영자 육안 리뷰로 종결 (MATCHED/MISMATCHED, 종결 번복은 새 첨부로만)
```

원칙: 프롬프트·필드 해석은 각 도메인 소유(클라이언트는 "이미지+프롬프트 → JSON"만), 판독 실패는
지어내지 않고 503, 이름류(상호명·성명)는 판정에 쓰지 않는 참고 정보, PII(주민번호·계좌번호)는
추출 자체를 배제.

## 단계별 산출물

### 1. shared-common 승격 + tax 재배선

- `common.ocr.VisionExtractionClient` — Gemini 호출·봉투 해체·코드펜스 제거를 도메인 중립화.
  스프링 빈이 아니라 서비스별 프로퍼티로 생성(제한 스캔 `@Import` 문제 회피). `106a15536`
- tax `GeminiTaxInvoiceOcrAdapter` 를 클라이언트 위로 재배선(동작·API 불변, 회귀 GREEN). `35f5dc0a5`
- 실버그: 생성자 2개(운영용+테스트 주입용)로 Spring 빈 생성 실패 → `@Autowired` 명시로 card·tax
  동시 수정(tax 는 `provider=gemini` 기동 시 터질 잠재 결함이었음).

### 2. card — 영수증 3자 대사 (`5bba556dd`)

- `expense_receipts`(V10) · 매입↔영수증↔지출보고서 대사: 총액 정확 일치, 거래일 매입일(KST) ±1일.
- 승인 게이트를 `ExpenseWorkflowService.approve` 에 삽입. ErrorCode 3종(`CARD_RECEIPT_*`).

### 3. insurance — 청약서류 (`379336651`)

- `application_documents`(V11) · 대사 축: 연 보험료·보장금액 정확 일치(수수료 12회 스케줄의 원천),
  청약일 접수일(KST) ±1일. 게이트는 완전판매 게이트 직후.
- 하우스 컨벤션(전용 타입 예외 + 컨트롤러 로컬 핸들러) 준수. 청약서 프롬프트에서 주민번호·연락처 배제.

### 4. loan — 담보서류 (`13ed725f7`)

- `collateral_documents` · 대사 3축: 감정평가액 정확 일치(한도 산정의 원천),
  **선순위 채권최고액 — 자기신고값의 현재 유일한 검증 수단**(서류 판독 불가 시 신고값 0 이면 통과,
  아니면 리뷰), 평가기준일 ±1일. 게이트는 담보 유효화(ACTIVE) 직전.
- 예외는 `LoanDomainException`(ErrorCode 경유), 도메인 generic IAE 금지 준수(guard OO 규칙).

### 5. deposit — 예치금 증빙, **지연 대사 변형** (`334e064d9`)

- 수기 기표는 즉시 반영·선행 애그리거트 없음 → 앵커를 **호출자 지정 멱등 키**
  `(sellerId, referenceType, referenceId)` 로 승격. 값 대사는 첨부 시점이 아니라
  **기표(credit/debit) 시점**에 요청 값과 대조(`DepositProofGate`).
- 이체일 허용폭은 다른 확산처(±1일)와 달리 **±3일**(수기 기표 리드타임 흡수, 프로퍼티 조정 가능).
- 대사 실패 판정은 기표 트랜잭션과 함께 롤백 → EXTRACTED 유지(요청 값 정정 후 재시도 가능).
  리뷰행 결함(신뢰도·이체일 판독 불가)만 첨부 시점에 NEEDS_REVIEW 영속(리뷰 경로 확보).
- **deposit 최초의 IT**(`DepositBootIT` — 실 Flyway + `ddl-auto: validate`) 신설.

### 6. 리뷰 큐 화면 (`5b4b516a3` 백엔드 + `f962cc6b9` 프론트)

- 목록 API 4종(settlement tax 큐 선례 `?status=&limit=`):
  deposit `GET /admin/deposits/proofs` · loan `GET /loans/secured/collateral-documents`(운영자 판정) ·
  insurance `GET /api/insurance/application-documents`(**PII 매처 신설** — ADMIN/MANAGER) ·
  card **최초 admin 표면** `/admin/expense-receipts`(내부망 표면 비노출, JWT 파생 리뷰어 + gateway
  predicate + ADMIN 매처).
- 화면 `/admin/system/proof-review`(ADMIN): 4개 탭, 근거(note) 입력 전 확정/반려 비활성.
  라우트+메뉴 2스텝(시드 `V20260814150000` + menuFallback + parity 8항목) 준수.

### 7. 전면 강제 정책 플래그 (`5a1af98f9`)

- 서비스별 `required`(기본 false). 켜면 미첨부 자체가 422 거절. 경계 2곳:
  - loan 은 **담보형에만** 적용(무담보 개인신용 제외)
  - deposit 은 **면제 referenceType**(기본 `SETTLEMENT`·`PAYOUT`) — 면제 없이 켜면 Kafka 자동
    정산 입금·지급 차감이 전부 멈춘다(테스트로 고정)

## 검증 (Definition of Done)

- 모듈 게이트: shared-common·settlement(tax 회귀)·card·insurance·loan·deposit 전부
  `test` + `jacocoTestCoverageVerification`(LINE 90%) GREEN — **Docker 기동 후 IT 포함**(로컬 IT skip
  으로 인한 가짜 판정 배제), 초기 3모듈은 격리 worktree(커밋 트리)에서 재현 검증.
- 프론트: vitest 전건 통과(당시 568건 — 이후 커버리지 게이트 90% 상향으로 증가)·lint 0·프로덕션 빌드·menu-route-gate 6/6·harness-audit healthy.
- 실측으로 잡은 결함: Spring 이중 생성자 빈 실패, Flyway 로스터 IT 갱신 누락, card 커버리지
  0.88 미달(어드민 컨트롤러 테스트 보강으로 회복), deposit 테이블명 오추정(BootIT 가 검출).

## 운영 반영 체크리스트

- [ ] 서비스별 Gemini 키 주입: `APP_CARD_RECEIPT_OCR_API_KEY` · `APP_INSURANCE_APPLICATION_OCR_API_KEY`
      · `APP_LOAN_COLLATERAL_OCR_API_KEY` · `APP_DEPOSIT_PROOF_OCR_API_KEY` (미설정 시 첨부 503 이 의도된 동작)
- [ ] 전면 강제 전환 시: 서비스별 `*_REQUIRED=true` — 소급 없음(승인/기표 시점 판정), 기존 미첨부
      건 처리 방침을 운영팀과 합의 후 켤 것. deposit 은 면제 목록 기본값 유지 필수.
- [ ] 리뷰 큐는 `/admin/system/proof-review` (ADMIN) — vite dev 프록시 미배선이라 실기동 검증은
      프로덕션 빌드(nginx→gateway) 경로.

## 남은 후보 (이번 범위 밖)

- 파일 원본 다운로드/미리보기 표면(현재 bytea 보관만, 조회 API 없음)
- 리뷰 큐 화면의 상태 필터(MISMATCHED 조회)·페이징 고도화
- insurance 청약↔가입설계 스냅샷(D-P2)까지 잇는 3자 대사 강화
