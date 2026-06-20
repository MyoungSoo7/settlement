# HARNESS — Lemuel (Settlement)

> Claude Code 개발 하네스 구성 — 헥사고날 + 정산 도메인 전용 에이전트 구성

**Last updated:** 2026-04-09

## 목적
정산 시스템은 **도메인 복잡도**와 **회계/감사 요건** 때문에 일반 백엔드 에이전트로 커버하기 어렵다. 본 하네스는 정산 도메인 전문 에이전트와 헥사고날 아키텍처 리뷰어를 별도 분리해 운영한다.

## 디렉토리 구조
```
.claude/
├── agents/
│   ├── db-query-architect.md         # DB 쿼리/인덱스 설계
│   ├── doc-maintainer.md             # 문서 일관성 유지
│   ├── hexagonal-arch-reviewer.md    # 포트/어댑터 경계 검증
│   ├── security-auditor.md           # 보안 감사
│   ├── settlement-domain-architect.md # 정산 도메인 설계
│   ├── settlement-logic-expert.md    # 정산 로직 심화
│   └── settlement-test-generator.md  # 정산 케이스 테스트 생성
└── commands/
    ├── agents/
    └── ai-dev-team.md
```

## 에이전트 사용 원칙
1. **정산 로직 변경 시** → `settlement-logic-expert` 로 리뷰 → `settlement-test-generator` 로 테스트 보강
2. **신규 도메인 추가** → `settlement-domain-architect` 먼저 → `hexagonal-arch-reviewer` 로 경계 검증
3. **쿼리/성능 이슈** → `db-query-architect`
4. **릴리즈 전** → `security-auditor` 로 회귀 확인

## 커맨드
- `/ai-dev-team` — 전사 역할 산출물 일괄 생성

## 확장 가이드
- 헥사고날 경계를 위반하는 변경은 자동화된 리뷰 대상 (PR 리뷰 워크플로우와 연계)
- 정산 전용 에이전트는 **회계 원칙**(차변/대변 균형, 수수료 처리)을 프롬프트에 내재화할 것

## 관련 문서
- `CLAUDE.md` — 에이전트 운용 규칙
- `STATUS.md` — 프로젝트 상태 (보안 라운드 2 진행 중)
- `README.md` — 아키텍처 개요
