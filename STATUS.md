# STATUS — Lemuel (Settlement)

> 전자상거래 주문·결제·정산 통합 시스템 (Spring Boot 4.0 / Java 25 / 헥사고날)

**Last updated:** 2026-04-09

## 현재 상태
- **활성 브랜치:** `perf/security-round2`
- **버전:** v0.2.0
- **최근 커밋:** `50599d1` ci: add lightweight PR review workflow

## 최근 진척
- 경량 PR 리뷰 워크플로우(GitHub Actions) 추가
- Swagger/OpenAPI 문서화 완료
- ai-dev-team 커맨드 + 17개 에이전트 프롬프트 도입
- JaCoCo 커버리지 리포트 설정
- Caffeine 캐시 확대, Elasticsearch TLS 설정
- 화면설계서/프로세스 정의서 (Mermaid)
- 사용자 매뉴얼(설치/실행/사용/설정/FAQ) 작성

## 진행 중
- 보안 라운드 2: 남은 TODO 정리, 하드닝 지속
- 테스트 커버리지 확대 (JaCoCo 기준)

## 다음 할 일
- [ ] `perf/security-round2` → `master` 머지
- [ ] 정산 도메인 E2E 테스트 보강
- [ ] 성능 벤치마크 (부하 테스트 시나리오)

## 주요 위험/메모
- 보안 강화 브랜치가 장기화되지 않도록 주기적 리베이스 필요
- ES 인증/TLS 설정이 로컬·스테이지·프로덕션 간 드리프트되지 않도록 설정 검증 자동화 필요

## 참고 문서
- `README.md` — 프로젝트 구조 및 개요
- `CLAUDE.md` — 에이전트 운용 가이드
- `HARNESS.md` — Claude Code 개발 하네스 구성
