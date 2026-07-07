# Fashion Copilot — 패션 이커머스 도메인 특화 Codex 플러그인 설계서

> Lemuel 커머스 플랫폼(order-service)을 무신사류 **패션 이커머스**로 가정하고,
> 패션 커머스 특유의 문제(사이즈 반품·한정판 드랍·리뷰 어뷰징·쿠폰 정합성)를 해결하는
> AI 코딩 에이전트(OpenAI Codex CLI, Claude Code 호환) 플러그인 설계.
> [`settlement-copilot`](./settlement-codex-plugin.md)의 자매편 — 동일한 3-Layer 구조를 커머스 도메인에 적용한다.

---

## 1. 배경과 문제 정의 — 무신사(패션 이커머스)의 4대 문제

패션 이커머스는 일반 커머스와 실패 모드가 다르다. 각 문제를 **이 코드베이스의 실제 코드 경로**에 매핑한다.

| # | 무신사형 문제 | 왜 패션에서 치명적인가 | 이 코드베이스의 대응 지점 |
|---|---|---|---|
| P1 | **사이즈·핏 반품** | 패션 반품률은 업계 최고 수준(20~30%), 대부분 사이즈/핏 사유. 반품 = 환불 + 역정산 + 재고 복원이 전부 얽힌 최다 빈도 금전 경로 | `RefundPaymentUseCase`(3-Phase, 비관적 락, 멱등키), `RefundSplitPaymentService`(tender 역순), `Coupon.calculateDiscountForRefund`(할인 안분) |
| P2 | **한정판 드랍 오버셀** | 라이브 발매 순간 동시 주문 폭주. 재고 1개를 2명에게 팔면 브랜드 신뢰 사고 | `decreaseStockIfAvailable` 원자적 조건부 UPDATE(`stock >= :qty`), `stock.depleted` ops signal, `variant/product.stock.decrease.*` 메트릭 |
| P3 | **리뷰 어뷰징** | 뒷광고·조작 리뷰가 구매 전환의 핵심(사이즈 후기)을 오염 — 무신사가 실제로 겪은 신뢰 문제 | `reviews UNIQUE(user_id, product_id)`, rating 1~5 검증. **구매 검증 링크 부재**(공백) |
| P4 | **쿠폰·프로모션 정합성** | 상시 할인/쿠폰이 매출 방어 수단 — 중복 사용·초과 할인·환불 안분 오류가 곧 마진 누수 | `CouponType`(FIXED/PERCENTAGE FLOOR), `maxDiscountAmount` 클램프, `UNIQUE(coupon_id,user_id)`, 원자적 `incrementUsageIfAvailable` |

범용 AI 에이전트는 이 규칙을 모른다. **Fashion Copilot** 은 (1) 도메인 지식, (2) 검증 도구,
(3) 가드레일 세 층을 에이전트에 주입해 "커머스 도메인 시니어가 옆에 앉은 것"과 같은 효과를 낸다.

### 대상 사용자 (페르소나)

| 페르소나 | 니즈 |
|---|---|
| 커머스 백엔드 개발자 | 재고 차감·환불·쿠폰 로직 수정 시 동시성/멱등/라운딩 규칙 자동 준수 |
| 드랍(발매) 운영자 | 드랍 전 재고 정합성 점검, 드랍 중 품절/거절율 실시간 관측을 에이전트에 위임 |
| CS·반품 운영자 | 환불 실패/이중 환불 의심 건 진단, 환불액·할인 안분 계산 근거 설명 |
| 프로모션 운영자 | 쿠폰 할인 시뮬레이션으로 기대값 검증, 중복 사용 방어 확인 |

---

## 2. 아키텍처 — settlement-copilot 과 동일한 3-Layer

```
┌─────────────────────────────────────────────────────────┐
│  AI Coding Agent (Codex CLI / Claude Code)              │
├─────────────────────────────────────────────────────────┤
│ ① Knowledge Layer — AGENTS.md + Skills (도메인 규칙)     │
│    반품·환불 규칙 · 드랍 재고 동시성 · 리뷰 무결성 · 쿠폰 │
├─────────────────────────────────────────────────────────┤
│ ② Tool Layer — MCP Server (읽기 전용 검증 도구)          │
│    refund_recon · refund_health · stock_pulse ·          │
│    coupon_simulate · refund_simulate                     │
├─────────────────────────────────────────────────────────┤
│ ③ Guardrail Layer — Hooks (커밋/실행 전 자동 차단)       │
│    금액 float 금지 · 재고 비원자 변경 차단 ·              │
│    쿠폰 usedCount 직접 조작 차단 · 배송지 PII 로깅 차단   │
└─────────────────────────────────────────────────────────┘
```

- MCP 서버는 **기존 표면만 프록시**한다: order `/internal/recon/*`(X-Internal-Api-Key),
  order `/actuator/prometheus`, 그리고 네트워크 불필요한 로컬 시뮬레이션.
  새 백엔드 엔드포인트가 필요한 도구는 로드맵(§8)으로 분리 — 플러그인만으로 즉시 동작한다.
- 듀얼 플랫폼: Claude Code 는 `.claude-plugin` 매니페스트로, Codex CLI 는 `install-codex.sh`
  (AGENTS.md 마커 병합 + prompts/skills 복사 + config.toml MCP 등록 + git pre-commit 폴백)로 설치.

---

## 3. Layer ① — Knowledge Layer (Skills)

| Skill | 트리거 | 내용 근거 (실측 코드) |
|---|---|---|
| `size-return-policy` | 반품/환불/교환 코드 작성·조사 시 | 환불 3-Phase(락 밖 스냅샷→REQUESTED 독립커밋→FOR UPDATE 재확정), 전액=자동 멱등키 `payment-{id}-full`·부분=호출자 필수키, 초과 환불 금지, 전액 도달 시에만 주문 REFUNDED, 분할결제 tender 역순 환불, 교환=환불+재주문 원칙, split 경로 refundId=null 원장 공백 주의 |
| `drop-stock-integrity` | 재고/드랍/품절 코드 작성·조사 시 | 원자적 조건부 UPDATE(`stock >= :qty`, 락 대기 0) 패턴 강제, affected==0 원인 분류, `increaseStock` 부활 규칙(DISCONTINUED 제외), REQUIRES_NEW 진입점, 재고 진실원천=variant(`options_json` 은 원본일 뿐), 드랍 전 체크리스트 |
| `review-integrity` | 리뷰 기능/어뷰징 조사 시 | 1인 1리뷰 UNIQUE(user_id, product_id) 하드 제약, rating 1~5, **구매 검증 부재 공백** — 어뷰징 진단은 주문 이력 교차조회로, 어뷰징 패턴 카탈로그(집중 시간대·rating 편중·비구매 리뷰) |
| `coupon-money-rules` | 쿠폰/할인/프로모션 코드 시 | FIXED=`min(할인,주문액)`, PERCENTAGE=FLOOR 절사+100% 초과 금지, `maxDiscountAmount` 클램프 순서, 1인 1매 UNIQUE + 원자적 `incrementUsageIfAvailable`(직접 `usedCount` 조작 금지), 환불 안분 `총할인×환불액÷주문액` FLOOR, orders 할인 비영속(SUM(line)−amount 역산) 주의 |

### AGENTS.md (상시 코어, ≈40줄)

- 금액(가격·할인·환불액)은 BigDecimal, 절사 정책(쿠폰 FLOOR)을 임의로 바꾸지 마라.
- 재고 변경은 원자적 조건부 UPDATE 경로로만 — 읽고-빼고-쓰는 코드를 만들지 마라.
- 쿠폰 사용량은 `incrementUsageIfAvailable` 로만 — `usedCount` 직접 증감 금지.
- 환불 코드에는 멱등키 규칙(전액=자동, 부분=필수)을 지켜라.
- 운영 데이터 조회는 MCP 도구로만, 배송지·연락처 PII 는 마스킹 없이 로깅 금지.

---

## 4. Layer ② — Tool Layer (MCP Server, 전부 읽기 전용)

| MCP Tool | 백엔드 | 용도 (문제 축) |
|---|---|---|
| `refund_recon` | order `/internal/recon/daily-totals` + `daily-counts` | P1 — 일자별 캡처/환불 금액·건수. 환불 금액이 비정상 급증하면 반품 이상 신호 |
| `refund_health` | Prometheus `refund_*` (requests/completed/failed/idempotency_key_reuse/amount/duration) | P1 — 환불 실패율·멱등키 재사용(재시도 폭풍)·처리시간. failed{reason} 분포로 원인 축 |
| `stock_pulse` | Prometheus `variant_stock_decrease_*`, `product_stock_decrease_*` | P2 — 차감 성공/거절 비율. 드랍 중 rejected 급증 = 품절 러시(정상 방어) vs 평시 rejected = 재고 노출 버그 의심 |
| `coupon_simulate` | 로컬 계산 (`CouponType`/`Coupon.calculateDiscount` 미러) | P4 — 할인·환불 안분 dry-run. FLOOR/클램프 기대값을 손계산 대신 검증 |
| `refund_simulate` | 로컬 계산 (`RefundPaymentUseCase` 규칙 미러) | P1 — 환불 가능액/전액·부분 판정/멱등키 요구/주문 상태 전이 dry-run |

- 금액 계산은 **BigInt + 명시적 FLOOR** (money-safety 규칙 — float 사용 금지, KRW 정수).
- 응답 PII 는 서버 측 deepMask (settlement-copilot 과 동일 — 배송지/연락처 키 추가).
- P3(리뷰)은 기존 읽기 표면이 없어 skill(진단 절차) + 커맨드로 커버, 도구는 로드맵(§8).

### 커맨드 (사용자 진입점)

| 커맨드 | 절차 |
|---|---|
| `/drop-check` | drop-stock-integrity skill 로드 → `stock_pulse` → 거절율 해석 → 드랍 전/중 체크리스트 판정 |
| `/return-audit [date]` | size-return-policy skill → `refund_recon(date)` → `refund_health` → 이상 시 `refund_simulate` 로 개별 건 재계산 |
| `/coupon-audit` | coupon-money-rules skill → 대상 코드/쿠폰의 기대 할인을 `coupon_simulate` 로 교차검증 |
| `/review-scan` | review-integrity skill → 리뷰·주문 데이터 교차조회 절차 안내 + 코드 레벨 무결성(UNIQUE·rating) 점검 |

---

## 5. Layer ③ — Guardrail Layer (Hooks + pre-commit 폴백)

| 규칙 | 심각도 | 근거 |
|---|---|---|
| `money-type-guard` | BLOCK | 커머스 금액 스코프(order/payment/coupon/product/cart/shipping 의 .java/.kt)에서 float/double 금액 선언·파싱, `new BigDecimal(double)` 금지 |
| `stock-atomicity-guard` | BLOCK | `UPDATE products/product_variants SET ... stock` 에 `stock >=` 조건 없는 SQL — 오버셀 직결. `.setStockQuantity(` 서비스 코드 사용은 WARN(조건부 UPDATE 경로 우회 의심) |
| `coupon-usage-guard` | BLOCK | `usedCount++`/`setUsedCount(` 직접 조작 — 원자적 `incrementUsageIfAvailable` 우회 = 초과 사용 |
| `pii-logging-guard` | BLOCK | 배송지·연락처·수취인(recipient/phone/address) 마스킹 없는 로깅 |
| `prod-db-guard` (command) | BLOCK | opslab DB 직접 쓰기(psql UPDATE/DELETE/...) |
| `event-produce-guard` (command) | WARN | `lemuel.*` 토픽 직접 produce (Outbox/멱등 우회) |
| `migration-guard` | WARN | Flyway 파일명 규칙, 파괴적 DROP |

Claude Code 는 PreToolUse(Write|Edit / Bash) 훅으로, Codex CLI 는 `.git/hooks/pre-commit` 폴백으로 동작.

---

## 6. 디렉터리 구조

```
fashion-copilot/
├── AGENTS.md               # ① 상시 코어 규칙 (Codex 자동 로드)
├── skills/                 # ① 상황별 도메인 지식 4종
├── commands/               # 진입점 4종 (/drop-check, /return-audit, /coupon-audit, /review-scan)
├── hooks/                  # ③ 가드레일 (Claude Code 훅 + git pre-commit 폴백)
│   └── guards/rules.mjs
├── mcp/server/index.mjs    # ② 읽기 전용 MCP 서버 (zero-dependency, Node 18+)
├── .claude-plugin/         # Claude Code 플러그인 + 마켓플레이스 매니페스트
├── .mcp.json               # Claude Code MCP 연결 (환경변수 확장)
├── install.mjs             # 단일 진입점 CLI — codex/claude/doctor/smoke/uninstall-codex (크로스 플랫폼)
├── install-codex.sh        # 하위 호환 래퍼 (→ install.mjs codex)
└── test/smoke.mjs          # 스모크 테스트 (네트워크 불필요 — MCP 왕복·가드·설치기 멱등성)
```

**설치 편의성 원칙**: Node 18+ 단일 의존(Windows PowerShell 에서 Git Bash 불필요),
모든 설치 동작 멱등(재실행=동기화), `doctor` 로 상태·연결을 조치 명령과 함께 진단,
`uninstall-codex` 로 흔적 없이 제거(타 플러그인 설정 보존 — 스모크 [3]이 검증).

환경변수: `ORDER_BASE_URL`(기본 :8088), `INTERNAL_API_KEY`(order 내부 API 키),
`COPILOT_ADMIN_TOKEN`(선택 — actuator 인증 환경용).

## 7. 보안 원칙

settlement-copilot 과 동일: GET 만 라우팅(read-only by construction), PII 서버측 마스킹,
최소 권한 키, 사내망 전제. 커밋 우회는 `--no-verify` + 사유 기록.

## 8. 로드맵 — 실측으로 확인된 데이터 공백

| Phase | 항목 | 선행 조건 (order-service 측 작업) |
|---|---|---|
| 2 | `return_reason_stats` — 사이즈/핏 반품 사유 분포 | `Refund.reason` 이 현재 유형(FULL/PARTIAL)·실패 메시지 겸용 → **반품 사유 enum + 컬럼 신설** 필요 |
| 2 | `review_integrity_scan` — 비구매 리뷰·집중 작성 탐지 | reviews↔orders 링크 부재 → 구매검증 조회 내부 API 신설 필요 |
| 3 | `oversell_check` — 재고 vs 판매 수량 대사 | `/internal/stock/recon` 성격의 읽기 API 신설 필요 |
| 3 | split 환불 원장 완결 | `TenderRefundExecutor.finalizeRefund` 의 refundId=null 원장 역분개 생략 해소 |
