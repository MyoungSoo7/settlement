# HARNESS — Lemuel (Settlement)

> Claude Code 개발 하네스 구성 — 헥사고날 + 정산/결제 도메인 전용 에이전트 구성

**Last updated:** 2026-06-24

## 목적
정산 시스템은 **도메인 복잡도**와 **회계/감사 요건**, 그리고 **MSA 경계(서비스 간 코드·DB 의존 0)** 때문에 일반 백엔드 에이전트로 커버하기 어렵다. 본 하네스는 정산 도메인 전문 에이전트와 헥사고날 아키텍처 리뷰어를 별도 분리해 운영한다.

## 디렉토리 구조
```
.claude/
├── agents/
│   ├── db-query-architect.md          # DB 쿼리/인덱스/ES 매핑 설계
│   ├── doc-maintainer.md              # 문서 일관성 유지 (API/ADR/README)
│   ├── hexagonal-arch-reviewer.md     # 포트/어댑터 경계 + 서비스 간 의존 방향 검증
│   ├── security-auditor.md            # 결제/정산 보안 감사
│   ├── settlement-domain-architect.md # 정산 도메인 설계 (수수료·주기·홀드백·역정산)
│   ├── settlement-logic-expert.md     # 정산 로직 심화/디버깅
│   └── settlement-test-generator.md   # 정산 케이스 테스트 생성
└── commands/
    ├── agents/                        # 역할별 산출물 생성 서브커맨드
    └── ai-dev-team.md                 # 전사 역할 산출물 일괄 생성
```

## 대상 코드베이스 (2026-06-24)
- **3 비즈니스 서비스** order / settlement / loan + API Gateway + `shared-common` 라이브러리
- **DB-per-service** (opslab / settlement_db / lemuel_loan) — 서비스 간 연계는 Kafka 이벤트 + 내부 대사 API 뿐
- order-service 내 도메인: user·order·payment·cart·shipping·product·category·coupon·review·game·recon·projectionbackfill·**rbac·menu·commoncode**(관리자 시스템)
- *reservation(시공 예약) 도메인은 제거됨* — 관련 에이전트 규칙도 폐기

## 에이전트 사용 원칙
1. **정산 로직 변경 시** → `settlement-logic-expert` 로 리뷰 → `settlement-test-generator` 로 테스트 보강
2. **신규 도메인 추가** → `settlement-domain-architect`(정산계) 또는 일반 헥사고날 설계 후 → `hexagonal-arch-reviewer` 로 경계 검증
3. **쿼리/성능/인덱스 이슈** → `db-query-architect`
4. **릴리즈 전** → `security-auditor` 로 회귀 확인
5. **MSA 경계 변경 시** → `hexagonal-arch-reviewer` 로 *서비스 간 코드 의존 0* / *cross-DB 0* 위반 여부 필수 검증 (ArchUnit 규칙과 연계)

## 커맨드
- `/ai-dev-team` — 전사 역할(PM/BA·아키텍트·백엔드·QA·보안 등) 산출물 일괄 생성

## 확장 가이드
- 헥사고날 경계를 위반하는 변경은 자동화된 리뷰 대상 (PR 리뷰 워크플로우 + ArchUnit 게이트와 연계)
- 정산 전용 에이전트는 **회계 원칙**(차변/대변 균형, 수수료 스냅샷, 역정산 음수 레코드)을 프롬프트에 내재화할 것
- 결제/환불처럼 **돈이 움직이는 경로**는 멱등성(Idempotency-Key)·동시성(비관락)·실패 롤백을 항상 점검 대상으로 둘 것

## 관련 문서
- `CLAUDE.md` — 에이전트 운용 규칙 / 아키텍처 컨텍스트
- `STATUS.md` — 프로젝트 상태 (DB-per-service 완료, loan 추가, 관리자 시스템 도입)
- `PORTFOLIO.md` — 면접용 1장 요약
- `README.md` — 아키텍처 개요
