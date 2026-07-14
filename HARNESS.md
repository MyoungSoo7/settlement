# HARNESS — Lemuel (Settlement)

> Claude Code 개발 하네스 구성 — 헥사고날 + 정산/결제/금융 도메인 전용 에이전트·스킬·커맨드·가드 구성

**Last updated:** 2026-07-12

## 목적
정산·금융 시스템은 **도메인 복잡도**와 **회계/감사 요건**, **MSA 경계(서비스 간 코드·DB 의존 0)** 때문에 일반 백엔드 에이전트로 커버하기 어렵다. 본 하네스는 (1) 도메인 전문 서브에이전트, (2) 서비스별 강제 규칙 스킬, (3) 운영/설명 커맨드, (4) 돈 경로 가드를 층으로 분리해 운영한다. 원칙: **결정적인 것은 훅·게이트로 강제, 판단이 필요한 것은 에이전트로 위임, 작성과 검증은 분리.**

## 디렉토리 구조
```
.claude/
├── agents/                            # 서브에이전트 (별도 컨텍스트, 역할 위임)
│   ├── db-query-architect.md          # DB 쿼리/인덱스/ES 매핑 설계
│   ├── doc-maintainer.md              # 문서 일관성 유지 (API/ADR/README)
│   ├── hexagonal-arch-reviewer.md     # 포트/어댑터 경계 + 서비스 간 의존 방향 검증
│   ├── security-auditor.md            # 결제/정산 보안 감사
│   ├── settlement-domain-architect.md # 정산 도메인 설계 (수수료·주기·홀드백·역정산)
│   ├── settlement-logic-expert.md     # 정산 로직 심화/디버깅
│   ├── settlement-test-generator.md   # 정산 케이스 테스트 생성
│   ├── gl-ledger-auditor.md           # 계정계 GL 복식부기·시산표·분개 매핑 정합 감사 (account + ledger)
│   └── event-contract-reviewer.md     # cross-service 이벤트 계약 드리프트·Outbox·멱등 검토 (ADR 0024)
├── skills/                            # 온디맨드 절차적 지식 (SKILL.md)
│   ├── {서비스}-rules/                # 12서비스 강제 도메인 규칙 (아래 참조)
│   ├── money-safety · ledger-invariants · idempotency-and-events   # 횡단 규칙
│   ├── recon-playbook · incident-runbooks · compliance-review      # 운영/리뷰
│   ├── settlement-integration-test                                 # Testcontainers 통합테스트 작성
│   ├── msa-service-wiring · event-contract-change · projection-view-ops  # 확장 절차 (서비스 배선·이벤트 계약·프로젝션)
│   └── socrates·wonder·reflect·refine·restate·evolve-step·ontology·interview-harness  # 요구사항 인터뷰 서브하네스
├── commands/                          # 슬래시 커맨드 (워크플로 진입점)
│   ├── settlement-explain · loan-credit-explain · investment-score-explain  # 산정 근거 풀이(CS/CEO)
│   ├── recon-check · oncall · ledger-verify · trial-balance-verify          # 운영 진단·검증
│   ├── fee-audit · compliance-scan                                          # 감사
│   ├── harness-check                                                        # 하네스 자기 진단(드리프트·가드·라우팅)
│   ├── ai-dev-team.md                 # 전사 역할 산출물 일괄 생성
│   └── agents/                        # 역할별 산출물 생성 서브커맨드
├── settings.json / settings.local.json  # 훅·권한 (PreToolUse/PostToolUse 가드, allowlist)
└── (worktrees/)                       # 격리 작업공간 (병렬 세션 충돌 회피)
```

## 대상 코드베이스
- **12 마이크로서비스** + API Gateway + `shared-common`(버전드 1.0.0) · **DB-per-service** · 서비스 간 연계는 Kafka 이벤트 + 내부 대사 API 뿐 — **cross-DB 0 · cross-code 0**(이것이 이 하네스가 지키는 핵심 불변식)
- 서비스 로스터·포트·DB·수치 정본 → `STATUS.md` · 모듈 경계·컨벤션 → `CLAUDE.md` · *reservation(시공 예약) 도메인 제거 완료(에이전트·규칙 폐기)*

## 서비스별 규칙 스킬 (온디맨드 로드)
`order-commerce` · `settlement-domain` · `loan-domain` · `investment-domain` · `account-domain` ·
`financial-data` · `economics-data` · `market-quotes` · `company-news` · `commondata-connector` ·
`operation-signal` · `ai-chat` — 각 서비스 로직 작성·수정·리뷰 시 해당 `*-rules` 스킬이 강제 규칙(상태머신·정책·경계)을 로드.

> **에이전트 로스터 설계 원칙 (의도된 공백)**: 전용 서브에이전트는 **고위험·상태보존 축**(정산·GL·이벤트 계약·헥사 경계·보안·쿼리)에만 둔다. 공개 read-only 위성(financial·economics·market·commondata)과 부가(operation·ai)는 상태 변이·회계 리스크가 낮아 **`*-rules` 스킬 + ArchUnit 게이트로 커버하는 것이 의도된 설계**다 — 서비스마다 에이전트를 만들지 않는다(로스터 비대화 = 안티패턴).

## 라우팅 맵 (작업 트리거 → 진입점) — 판단 전 반드시 스캔
> 유형: 🤖=서브에이전트(별도 컨텍스트) · 📘=스킬(온디맨드 규칙) · ⌘=슬래시 커맨드(워크플로) · 🚦=기계 게이트
>
> | 작업 트리거 | 진입점 |
> |---|---|
> | 정산 로직 작성·변경 | 📘`settlement-domain-rules`+`money-safety`+`ledger-invariants` → 🤖`settlement-logic-expert` → 🤖`settlement-test-generator` |
> | 임의 서비스 도메인 작업 | 📘 해당 `{서비스}-rules` 로드 후 구현 → 🤖`hexagonal-arch-reviewer` 경계 검증 |
> | 이벤트 발행·컨슈머·멱등 | 📘`idempotency-and-events` → 🤖`event-contract-reviewer` (schema↔producer↔consumer 3자 정합·Outbox·멱등) |
> | cross-service 토픽 추가·페이로드 변경 | 📘`event-contract-change` (스키마·샘플·양방향 계약 테스트 배선) → 🤖`event-contract-reviewer` → 🚦이벤트 계약 테스트 |
> | 신규 서비스·도메인 추가 / 배선 404 | 📘`msa-service-wiring` (5곳 배선 체크리스트) → 🚦`harness-audit.mjs` 셀프체크 |
> | 프로젝션 뷰 추가·드리프트·백필 | 📘`projection-view-ops` (ADR 0020) + 📘`recon-playbook`·`incident-runbooks` |
> | 계정계 GL·시산표·분개 | 🤖`gl-ledger-auditor` (차1대1 균형·6토픽 매핑·2단 멱등·소비전용) + 📘`ledger-invariants`·`account-domain-rules` |
> | 쿼리·인덱스·ES 매핑·성능 | 🤖`db-query-architect` |
> | MSA 경계 변경 | 🤖`hexagonal-arch-reviewer` → 🚦ArchUnit (*코드 의존 0 / cross-DB 0* 위반 차단) |
> | 금액 다루는 코드 | 📘`money-safety` (BigDecimal 강제·라운딩·직렬화) |
> | 원장 전표·복식부기 | 📘`ledger-invariants` → ⌘`/ledger-verify`·`/trial-balance-verify` |
> | 통합테스트 작성 | 📘`settlement-integration-test` (Testcontainers) / 🤖`settlement-test-generator` |
> | 릴리즈 전 보안·컴플라이언스 | 🤖`security-auditor` + ⌘`/compliance-scan` (diff PII/이력/감사/권한) |
> | 수수료·홀드백 감사 | ⌘`/fee-audit` (도메인 정책 + simulate 교차검증) |
> | 온콜·장애·알람 | ⌘`/oncall` + 📘`incident-runbooks` |
> | 대사 불일치 조사 | ⌘`/recon-check` + 📘`recon-playbook` |
> | CS/CEO 산정 근거 문의 | ⌘`/settlement-explain`·`/loan-credit-explain`·`/investment-score-explain` |
> | 요구사항 모호 | 📘`interview-harness`(=`socrates`+`evolve-step`+`ontology` 루프) |
> | 전사 역할 산출물 일괄 | ⌘`/ai-dev-team` (+ `commands/agents/*` 서브커맨드) |
> | 하네스 자기 진단·드리프트 | ⌘`/harness-check` (audit + 가드 + `--fix` 로 STATUS 수치 자동 갱신) → 🚦`harness-audit.mjs` |
>
> **원칙:** 결정적인 것은 🚦게이트로 강제 · 판단 필요한 것은 🤖에이전트로 위임 · 작성과 검증은 분리(자기 승인 금지).

## 도구 접근 (MCP + 플러그인 독립 이중 경로)
운영/정합 데이터 접근은 **운영 DB 직접 접속 금지** — 아래 두 경로 중 하나만 쓴다. MCP 미설치(CI·새 클론·Codex)에서도 하네스가 죽지 않도록 **저장소 네이티브 경로를 항상 병존**시킨다.
- **경로 A — MCP(리치, 플러그인 설치 시)**: settlement/invest-copilot MCP 도구(`recon_run`·`ledger_entries`·`projection_status`·`outbox_status`·`integrity_check`·`trial_balance` 등). 대화형 조사에 최적.
- **경로 B — 저장소 네이티브(플러그인 0 의존, CI 가능)**:
  - `node scripts/harness/harness-audit.mjs` — 하네스 자기 진단(STATUS 수치 드리프트·라우팅 dangling·가드 훅 경로 실존·인벤토리)
  - `node scripts/harness/guard.mjs --staged` — 돈/경계/이력 불변식 가드
  - `./gradlew :<module>:test`·`:jacocoTestCoverageVerification` — 정합 검증(측정 정답)
  - 서비스 자체 `/admin/integrity`·`/api/account/trial-balance` 조회 API(읽기 전용)
- **불변식**: psql/pg_dump/kafka produce 로 운영 데이터에 직접 손대는 명령을 만들지 않는다(가드가 `check-command` 로 차단).

**MCP 도구 ↔ 플러그인 독립 폴백 매핑** (조용한 "MCP 단독" 금지 — 모든 능력에 폴백 또는 런타임 경계 명시):
> | MCP 도구(경로 A) | 폴백(경로 B) | 종류 |
> |---|---|---|
> | `integrity_check` | 서비스 `/admin/integrity` API | 정적/API |
> | `trial_balance`·`ledger_entries` | `/api/account/trial-balance`·`/api/ledger` (ADMIN) + 🤖`gl-ledger-auditor` 코드 감사 | API/정적 |
> | `recon_run`·`order_recon_totals` | `/internal/recon` + ⌘`/recon-check` 절차 | API |
> | (하네스 정합) | `harness-audit.mjs`·`guard.mjs` | **정적, 0 의존** |
> | `projection_status`·`outbox_status`·`stuck_states` | **런타임 전용** — 폴백 없음(라이브 컨슈머 lag/적체는 실행 중 시스템 필요). MCP 미설치 시 Prometheus/Actuator 직접 조회로 대체, 코드 정합은 정적 경로로 분리 검증 |
>
> 정적·계약·정합은 경로 B 로 **CI/오프라인 검증 가능**, 라이브 상태값만 런타임 전용으로 격리된다(이것이 남은 경계 — 코드가 아니라 실행 중 시스템의 속성이므로 하네스로 제거 불가).

## 검증 게이트 (ground truth — 모델 주장이 아니라 기계 판정)
- **ArchUnit** — 헥사고날 경계·서비스 간 의존 방향
- **JaCoCo** — CI LINE 90% / 핵심 도메인 INSTRUCTION 80% (측정은 게이트 태스크가 정답)
- **이벤트 계약 테스트** — cross-service 10토픽 스키마 드리프트 빌드 시점 차단 (ADR 0024)
- **돈 경로 가드(저장소 추적)** — `scripts/harness/guard.mjs`: 실시간 PreToolUse(exit 2 차단) + git pre-commit(`core.hooksPath`, `node scripts/harness/install-hooks.mjs`) 이중. 플러그인 독립 — BigDecimal·이력불변·MSA 경계·account 발행금지·market 밸류에이션·hackathon/pwc 커밋 위반 차단. `--no-verify` 우회 금지. copilot 플러그인 가드가 있으면 2차 레이어로 병존.
- **하네스 자기 진단** — `scripts/harness/harness-audit.mjs`: 문서 드리프트를 규율이 아닌 **기계 게이트**로 승격(과거 STATUS 3주 방치 재발 방지).
- **CI 강제** — `.github/workflows/harness-guard.yml`: PR/푸시마다 변경 파일 가드(`guard.mjs --list`) + 자기 진단을 **로컬 설정과 무관하게** 실행(훅 미설치·`--no-verify` 우회를 CI가 재차단). 기존 `ci.yml`(빌드·테스트·커버리지)와 병존.

## 하드스톱 — 절대 금지 (위반 = 회계·아키텍처 손상 · 정본은 CLAUDE `🚫 핵심 가드레일`)
- 금액에 `double`/`float` 금지 → `BigDecimal` 만 · `POSTED` 전표 수정 금지 → 역분개만 · 반쪽 전표 금지 → 차1·대1 균형 팩토리만
- `settlement`→`order` import·cross-DB 조인 금지 · 도메인→어댑터 import 금지 · account 이벤트 발행 금지 · market PER/PBR 계산 금지
- 셀러 식별자를 요청 파라미터로 신뢰(IDOR) 금지 → JWT 주체 파생·소유권 대조 · `hackathon/`·`pwc/` 커밋 금지 · `main` 직접 push 금지
> 위는 압축 신호(요약). 전체 근거·서비스별 강제 규칙은 CLAUDE 🚫 섹션과 `*-rules` 스킬이 정본. 기계 차단은 ArchUnit·돈경로 가드가 담당.

## 완료 판정(DoD) — 선언 전 이 게이트를 통과했는가 (LLM 판단 아님, 기계가 정답)
- [ ] `./gradlew :<module>:test` 통과 (관련 모듈 전부)
- [ ] `:<module>:jacocoTestCoverageVerification` — CI LINE 90% / 핵심 도메인 INSTRUCTION 80% 통과
- [ ] **MSA 경계 변경 시** ArchUnit 위반 0 (`settlement`↔`order` 코드·cross-DB 의존 0 확인)
- [ ] **cross-service 토픽 변경 시** 이벤트 계약 테스트(ADR 0024) 통과 — 프로듀서·컨슈머 양방향
- [ ] 돈 경로 가드 통과 · `--no-verify` 미사용 · `hackathon/`·`pwc/` 미커밋
- [ ] **작성과 검증 분리** — 같은 컨텍스트 자기 승인 금지, `code-reviewer`/`verifier` 별도 패스로 증거 수집
- [ ] 문서 휘발성 수치를 바꿨으면 재현 명령 재실행 + `STATUS.md#핵심 수치` 갱신
> 하나라도 미충족이면 "완료"라고 쓰지 않는다. 커밋은 `develop` 항목별 개별 커밋(PowerShell 은 `git commit -F <file>`).
> `main` 반영은 PR·**squash 만**·필수 CI 2종 (직접 push 금지 — 보호 브랜치). 운영 배포는 강한 `JWT_SECRET`·`internal-key-required=true`·외부 API 키 주입 확인.

## 드리프트 방지 규약 (문서 최신성)
- **휘발성 수치**(마이그레이션·테스트·서비스 수 등)는 값만 적지 말고 **재현 git 명령을 병기**한다 → 수치가 falsifiable 해져 조용한 드리프트가 불가능해진다. 정본은 `STATUS.md#핵심 수치`.
- 수치 집계는 반드시 **git-tracked 소스 기준**(`git ls-files`) — `find` 는 `build/` 사본과 `.claude/worktrees/` 에이전트 사본을 이중 집계하므로 금지(과거 마이그레이션 224 유령 수치의 원인).
- 문서 상호참조는 **단일 출처**를 가리킨다: 수치→STATUS, 기능·API→SPEC, 규칙→`*-rules` 스킬, 경계·컨벤션→CLAUDE. 같은 사실을 두 곳에 복제하지 않는다.

**셀프체크** (수치 드리프트 + 라우팅 맵 dangling 진입점을 한 번에 노출 — 하네스 수정 후 실행):
```bash
# 1) STATUS 핵심 수치 재검증
git ls-files '*/src/main/resources/db/migration/*.sql' | wc -l   # =110
git ls-files '*/src/test/*Test.java' '*/src/test/*Tests.java' '*/src/test/*IT.java' | wc -l  # =517
# 2) 라우팅 맵 진입점 존재 검증 — 아이콘(🤖📘⌘) 줄의 backtick 토큰만 스코프 (누락 시 출력, 무출력=정상)
grep -E '🤖|📘|⌘' HARNESS.md | grep -oE '`[a-z][a-z-]+`' | tr -d '`' | sort -u | while read n; do \
  [ -e ".claude/agents/$n.md" ] || [ -d ".claude/skills/$n" ] || [ -e ".claude/commands/$n.md" ] || echo "DANGLING: $n"; done
```

## 확장 가이드 (하네스를 늘릴 때)
- 새 도메인 전용 에이전트·스킬을 만들 땐 관련 **하드스톱 + `*-rules`** 를 프롬프트에 내재화(위 하드스톱 섹션이 정본).
- 돈 경로(결제·환불·지급·대출·투자) 신규 코드는 멱등(Idempotency-Key)·동시성(비관락)·실패 롤백을 점검 항목에 포함.
- 새 서비스 추가 시 `{서비스}-rules` 스킬 + 커맨드 + gateway/스캔 배선(5곳, 절차 정본: 📘`msa-service-wiring`)을 함께 배선하고, 라우팅 맵에 트리거 행 1개 추가 후 **셀프체크** 재실행.

## 관련 문서
- `CLAUDE.md` — 에이전트 운용 규칙 / 아키텍처 경계·컨벤션
- `SPEC.md` — 전체 기능명세(엔드포인트·도메인 규칙·이벤트 카탈로그)
- `STATUS.md` — 프로젝트 상태 (12서비스, 계정계·투자 확장, ADR 25)
- `PORTFOLIO.md` — 면접용 1장 요약 · `README.md` — 아키텍처 개요
- `docs/adr/` — 아키텍처 결정 기록 25개
