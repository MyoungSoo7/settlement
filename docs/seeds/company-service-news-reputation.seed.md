# Seed — company-service 뉴스·평판·문서함 as-is 사양

> **상태: CONFIRMED** (2026-07-19) · 정본 데이터: [`company-service-news-reputation.seed.yaml`](./company-service-news-reputation.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화. 자매 Seed: [order](./order-service-core-commerce.seed.md) · [settlement](./settlement-service-accounting-core.seed.md).

## Goal (한 줄)

**company-service(뉴스 수집·평판 스코어링·문서함, ADR 0023)의 현행 동작 — url_hash 멱등 수집,
INSERT-only 평판 스냅샷, 감성분석 fail-open 폴백 체인, 문서함 역할 게이트 — 을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함 | 제외 |
|------|------|
| article (수집·url_hash 멱등·본문 미저장) | audit 서브패키지 내부 |
| reputation (산식 v1·등급·INSERT-only·등급변동 이벤트) | 네이버 API 페이로드 상세 |
| sentiment (4구현체 폴백 체인·캐시) | |
| document (문서함·역할 게이트) | |
| 발행 1 + 소비 1토픽 계약 표면 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **본문 미저장 + url_hash 멱등** — 필드 8개뿐(body 없음), title 500·summary 2000 절단. 정규화 URL(fragment 만 제거) SHA-256, 3층 방어(seenInBatch → existsByUrlHash → DIVE catch), `articles.url_hash UNIQUE` (`Article.java:57-101`, `V1:22`).
2. **평판 산식 v1** — `score = 100 − round(100×weightedPenalty/(total×3))`, 빈 목록=100. 가중치 FIN/LEGAL/GOV 3 · LABOR/PRODUCT 2 · 미분류 1. 등급 A≥80/B≥60/C≥40/D≥20/E (`ReputationScore.java:76-79`).
3. **INSERT-only 스냅샷** — 하루 1건 UNIQUE + saveIfAbsent + **DB 트리거가 UPDATE/DELETE 봉쇄** (V20260716306000). 저장+등급변동 이벤트 발행은 한 트랜잭션.
4. **감성 fail-open** — Gemini(쿼터 가드) → 실패 시 Keyword 폴백, 미지 라벨 → NEUTRAL, (url_hash, provider) 캐시 (`QuotaGuardedSentimentAnalyzer.java:53-79`).
5. **문서함 게이트** — GET 2경로 `hasAnyRole(ADMIN,MANAGER)` 를 공개 permitAll 보다 먼저 선언. 파일명 경로문자 차단·Content-Type 서버 파생·≤20MB (`SecurityConfig.java:87-92`).
6. **셀러 링크는 수동만** — user.registered 에 기업 키 없음 → 축적만, admin 명시 링크(1셀러 1기업).

## 이벤트 계약

**발행 1**: `company.reputation_changed` (Outbox 경유, 등급 변동 시만) → loan 소비.
**소비 1**: `user.registered` (group `lemuel-company`, IdempotentEventConsumer).
멱등: outbox event_id UNIQUE + processed_events PK + prune 보존 ≥7일.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 산식 v1·등급·가중치 일치 | `:company-service:test` 도메인 테스트 |
| AC-2 | 발행 1 + 소비 1토픽 계약 일치 | `CompanyReputationEventContractTest` + `EventContractConsumerTest` |
| AC-3 | 헥사고날 위반 0 | `HexagonalArchitectureTest` |
| AC-4 | LINE ≥ 90% · 도메인 INSTRUCTION ≥ 80% | `:company-service:jacocoTestCoverageVerification` |
| AC-5 | 문서함 역할 게이트 회귀 0 | `CompanyDocumentSecurityTest` |
| AC-6 | 스냅샷 append-only | DB 트리거 (ERRCODE 23514) |

## Known Issues (발견만 기록)

- **KI-1**: 쿼터 초과분이 키워드 결과로 캐시 고정(LLM 재승격 없음) — 문서화된 트레이드오프, 개선 후보.
- **KI-2**: 문서 게이트가 SecurityConfig 규칙 순서에만 의존 — 2차 방어 없음.
- **KI-3**: 문서 바이트 DB 직저장 — 볼륨 리스크 (컬럼 주석 명시).
- **KI-4**: 문서 드리프트 3건 (ADR 0023 gateway 문구·스킬 경고·SecurityConfig javadoc) — 코드↔규칙 불일치는 0건.
