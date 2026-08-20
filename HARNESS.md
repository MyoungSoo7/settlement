# HARNESS — Lemuel (Settlement)

> Claude Code 개발 하네스 구성 — 헥사고날 + 정산/결제/금융 도메인 전용 에이전트·스킬·커맨드·가드 구성

**Last updated:** 2026-08-15

## 목적

정산·금융 시스템은 **도메인 복잡도**와 **회계/감사 요건**, **MSA 경계(서비스 간 코드·DB 의존 0)** 때문에 일반 백엔드 에이전트로 커버하기 어렵다. 본 하네스는 **5계층**으로 분리해 운영한다 — (1) 도메인 전문 서브에이전트(판단 위임), (2) 서비스별 강제 규칙 스킬(온디맨드 지식),
(3) 운영/설명 커맨드(워크플로 진입점), (4) 돈 경로·경계 가드와 검증 게이트(기계 차단), (5) 라우터·텔레메트리(권장 주입과 관측).
원칙: **결정적인 것은 훅·게이트로 강제, 판단이 필요한 것은 에이전트로 위임, 작성과 검증은 분리.**
계층 (4)·(5)는 `scripts/harness/` 에 저장소 추적으로 구현되어 **플러그인·MCP 없이도 동작**한다(이 하네스의 이식성 전제).

## 디렉토리 구조

하네스는 **두 축**으로 나뉜다 — `.claude/`(모델에게 주는 지식·역할)와 `scripts/harness/`(기계가 강제하는 실행 코어).
전자는 플러그인·런타임에 따라 로드가 달라지지만, 후자는 **저장소에 추적되어 CI·새 클론·Codex 에서도 동일하게 동작**한다.

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
│   ├── {서비스}-rules/                # 16서비스 전체 강제 도메인 규칙 (아래 참조)
│   ├── money-safety · ledger-invariants · idempotency-and-events   # 횡단 규칙
│   ├── recon-playbook · incident-runbooks · compliance-review      # 운영/리뷰
│   ├── delta-review                                                # diff 위험축 트리아지(어디를 먼저 볼지) — 리뷰 진입 기준
│   ├── debugging-discipline · tdd-discipline · verify-before-done  # 절차 규율(플러그인 독립 — 외부 스킬 위임 금지)
│   ├── settlement-integration-test                                 # Testcontainers 통합테스트 작성
│   ├── msa-service-wiring · event-contract-change · projection-view-ops  # 확장 절차 (서비스 배선·이벤트 계약·프로젝션)
│   ├── oo-score · hookify-to-guard                                 # OO 5축 재채점(LLM 판정) · 훅 규칙 → guard 이식
│   └── socrates·wonder·reflect·refine·restate·evolve-step·ontology·interview-harness  # 요구사항 인터뷰 서브하네스
├── commands/                          # 슬래시 커맨드 (워크플로 진입점)
│   ├── settlement-explain · loan-credit-explain · investment-score-explain  # 산정 근거 풀이(CS/CEO)
│   ├── recon-check · oncall · ledger-verify · trial-balance-verify          # 운영 진단·검증
│   ├── fee-audit · compliance-scan · delta-review                           # 감사·리뷰
│   ├── harness-check                                                        # 하네스 자기 진단(드리프트·가드·라우팅)
│   ├── ai-dev-team.md                 # 전사 역할 산출물 일괄 생성
│   └── agents/                        # 역할별 산출물 생성 서브커맨드
├── settings.json / settings.local.json  # 훅·권한 (PreToolUse 가드·라우터, SessionStart 텔레메트리, allowlist)
├── harness/                           # 하네스 런타임 (gitignore — logs/ 텔레메트리 jsonl, state/ 라우터 세션 상태 · 14일 GC 는 라우터가 기회적 수행)
└── (worktrees/)                       # 격리 작업공간 (병렬 세션 충돌 회피)

scripts/harness/                       # ★ 실행 코어 — 저장소 추적, 플러그인·MCP 0 의존 (CI 에서 그대로 재실행)
├── guard.mjs · hooks/pre-commit · install-hooks.mjs   # 불변식 가드 3중 강제(실시간·커밋·CI)
├── skill-router.mjs                   # 편집 경로 → *-rules 스킬 리마인더 주입 (권장의 기계화)
├── harness-audit.mjs                  # 하네스 자기 진단 (문서 드리프트·라우팅 dangling·훅 무결성)
├── telemetry.mjs · telemetry-report.mjs · session-metrics.mjs   # 관측 계층 (적재·집계·KPI)
├── interview-harness.mjs              # 요구사항 인터뷰 루프(Claude/Codex 듀얼 플랫폼 계약)
├── manifest.json                      # 하네스 구성요소 추적 목록 — CI 가 git ls-files 로 실존 검증
└── test/*.test.mjs                    # 하네스 자기 테스트 — `node --test scripts/harness/test/*.test.mjs`
                                       #   (개수는 세어 쓴다: `git ls-files 'scripts/harness/test/*.test.mjs' | wc -l`)

.codex/{skills,agents,prompts}/        # Codex 미러 — interview-harness 계열은 Claude/Codex 양쪽 정본 쌍으로
                                       #   유지되고 manifest 의 criticalContractPairs 가 드리프트를 차단
```

## 대상 코드베이스

- **18 마이크로서비스** + API Gateway + `shared-common`(버전드 1.0.0) · **DB-per-service** · 서비스 간 연계는 Kafka 이벤트 + 내부 대사 API 뿐 — **cross-DB 0 · cross-code 0**(이것이 이 하네스가 지키는 핵심 불변식)
- 서비스 로스터·포트·DB·모듈 경계·컨벤션 → `CLAUDE.md` · _reservation(시공 예약) 도메인 제거 완료(에이전트·규칙 폐기)_

## 서비스별 규칙 스킬 (온디맨드 로드)

`order-commerce` · `settlement-domain` · `loan-domain` · `investment-domain` · `account-domain` ·
`financial-data` · `economics-data` · `market-quotes` · `company-news` · `commondata-connector` ·
`operation-signal` · `ai-chat` · `card-service` · `insurance-domain` · `deposit-domain` · `organization-domain` ·
`board-domain` — 각 서비스 로직 작성·수정·리뷰 시 해당 `*-rules` 스킬이 강제 규칙(상태머신·정책·경계)을 로드.
로드는 규율이 아니라 `skill-router.mjs` 가 편집 경로를 보고 **자동 주입**한다(아래 "강제 지점").

> **커버리지 완결(2026-08-15)**: 18서비스 전부가 전용 `*-rules` 스킬 + 라우터 `ROUTES` 행을 갖는다
> (둘은 같은 사실의 두 표현 — `skill-router.test.mjs` 가 회귀 방지). 마지막 3개의 해소 이력:
> 돈 경로 우선 부채였던 `insurance-domain-rules`(완전판매 게이트·25%룰·환수/12회 분할)·
> `deposit-domain-rules`(잔고 단일 진실원·hold/offset 이중사용 차단), 그리고 후순위였던
> `organization-domain-rules`(발행 전용 경계·활성 OWNER ≥1·card 프로젝션 계약 드리프트 3종).

> **에이전트 로스터 설계 원칙 (의도된 공백)**: 전용 서브에이전트는 **고위험·상태보존 축**(정산·GL·이벤트 계약·헥사 경계·보안·쿼리)에만 둔다. 공개 read-only 위성(financial·economics·market·commondata)과 부가(operation·ai)는 상태 변이·회계 리스크가 낮아 **`*-rules` 스킬 + ArchUnit 게이트로 커버하는 것이 의도된 설계**다 — 서비스마다 에이전트를 만들지 않는다(로스터 비대화 = 안티패턴).

## 라우팅 맵 (작업 트리거 → 진입점) — 판단 전 반드시 스캔

> 유형: 🤖=서브에이전트(별도 컨텍스트) · 📘=스킬(온디맨드 규칙) · ⌘=슬래시 커맨드(워크플로) · 🚦=기계 게이트
>
> | 작업 트리거                                | 진입점                                                                                                                                            |
> | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- |
> | 정산 로직 작성·변경                        | 📘`settlement-domain-rules`+`money-safety`+`ledger-invariants` → 🤖`settlement-logic-expert` → 🤖`settlement-test-generator`                      |
> | 임의 서비스 도메인 작업                    | 📘 해당 `{서비스}-rules` 로드 후 구현 → 🤖`hexagonal-arch-reviewer` 경계 검증                                                                     |
> | 이벤트 발행·컨슈머·멱등                    | 📘`idempotency-and-events` → 🤖`event-contract-reviewer` (schema↔producer↔consumer 3자 정합·Outbox·멱등)                                          |
> | cross-service 토픽 추가·페이로드 변경      | 📘`event-contract-change` (스키마·샘플·양방향 계약 테스트 배선) → 🤖`event-contract-reviewer` → 🚦이벤트 계약 테스트                              |
> | 신규 서비스·도메인 추가 / 배선 404         | 📘`msa-service-wiring` (5곳 배선 체크리스트) → 🚦`harness-audit.mjs` 셀프체크                                                                     |
> | 프로젝션 뷰 추가·드리프트·백필             | 📘`projection-view-ops` (ADR 0020) + 📘`recon-playbook`·`incident-runbooks`                                                                       |
> | 계정계 GL·시산표·분개                      | 🤖`gl-ledger-auditor` (차1대1 균형·6토픽 매핑·2단 멱등·소비전용) + 📘`ledger-invariants`·`account-domain-rules`                                   |
> | 법인카드 한도·발급·상태                    | 📘`card-service-rules` (재원 F 공식·`master ≥ Σsub` 비관적 락·하향 클램프·재원 폴백 금지) + 📘`money-safety` → 🚦`CardIssuanceLimitConcurrencyIT` |
> | 보험 설계·청약·계약·수수료·방카            | 📘`insurance-domain-rules` (완전판매 게이트 2단·25%룰·환수 24개월·12회 분할) + 📘`money-safety`                                                   |
> | 예치금 원장·hold/offset·상계               | 📘`deposit-domain-rules` (잔고 단일 진실원·이중사용 차단·referenceType 불변) + 📘`ledger-invariants`                                              |
> | 게시판 정의·스킨·접근 정책                 | 📘`board-domain-rules` (정의가 글 규칙 소유·스킨↔정책 정합·역할 allowlist·발행 0/소비 0·메뉴는 order 소유)                                        |
> | 조직·멤버십·역할(OWNER/MANAGER/STAFF)      | 📘`organization-domain-rules` (발행 전용 경계·활성 OWNER ≥1·card 프로젝션 계약) → 🤖`event-contract-reviewer` (페이로드 변경 시)                  |
> | 쿼리·인덱스·ES 매핑·성능                   | 🤖`db-query-architect`                                                                                                                            |
> | MSA 경계 변경                              | 🤖`hexagonal-arch-reviewer` → 🚦ArchUnit (_코드 의존 0 / cross-DB 0_ 위반 차단)                                                                   |
> | OO 설계 채점·리팩터링 회귀 판정            | 📘`oo-score` (3인 패널 중앙값 ≥9.5) — 결정적 불변식은 🚦`guard.mjs` OO-\* + `oo-gate.test.mjs` 가 선차단                                          |
> | 금액 다루는 코드                           | 📘`money-safety` (BigDecimal 강제·라운딩·직렬화)                                                                                                  |
> | 원장 전표·복식부기                         | 📘`ledger-invariants` → ⌘`/ledger-verify`·`/trial-balance-verify`                                                                                 |
> | 통합테스트 작성                            | 📘`settlement-integration-test` (Testcontainers) / 🤖`settlement-test-generator`                                                                  |
> | PR·브랜치 diff 리뷰 착수 ("어디부터 볼까") | 📘`delta-review` (경로 시그널 → P0~~P2 위험축 A~~K, 세로=안에서 밖으로·가로=프로듀서/계약/컨슈머 3자) → ⌘`/delta-review` → 축별 🤖위임            |
> | 릴리즈 전 보안·컴플라이언스                | 🤖`security-auditor` + ⌘`/compliance-scan` (diff PII/이력/감사/권한)                                                                              |
> | 수수료·홀드백 감사                         | ⌘`/fee-audit` (도메인 정책 + simulate 교차검증)                                                                                                   |
> | 온콜·장애·알람                             | ⌘`/oncall` + 📘`incident-runbooks`                                                                                                                |
> | 대사 불일치 조사                           | ⌘`/recon-check` + 📘`recon-playbook`                                                                                                              |
> | CS/CEO 산정 근거 문의                      | ⌘`/settlement-explain`·`/loan-credit-explain`·`/investment-score-explain`                                                                         |
> | 기능 구현·버그픽스 착수                    | 📘`tdd-discipline` (실패 테스트 먼저 → 🚦JaCoCo 가 정답) — 라우터가 세션 첫 소스 편집에 1회 주입                                                  |
> | 버그·테스트 실패·예상 밖 동작              | 📘`debugging-discipline` (원인 규명 전 수정 금지 · 가설 3연속 기각 시 중단)                                                                       |
> | "완료" 선언·커밋 직전                      | 📘`verify-before-done` (DoD 게이트 실행·증거 병기·자기 승인 금지)                                                                                 |
> | 요구사항 모호                              | 📘`interview-harness`(=`socrates`+`evolve-step`+`ontology` 루프)                                                                                  |
> | 전사 역할 산출물 일괄                      | ⌘`/ai-dev-team` (+ `commands/agents/*` 서브커맨드)                                                                                                |
> | hookify 규칙 생성·수정 / "훅 굳혀줘"       | 📘`hookify-to-guard` (캡처는 임시, 정본은 guard.mjs 3중 강제 — 이식 후 원본 삭제) — 라우터가 `hookify.*.local.md` 편집 시 주입                    |
> | 하네스 자기 진단·드리프트                  | ⌘`/harness-check` (audit + 가드) → 🚦`harness-audit.mjs`                                                                                          |
>
> **원칙:** 결정적인 것은 🚦게이트로 강제 · 판단 필요한 것은 🤖에이전트로 위임 · 작성과 검증은 분리(자기 승인 금지).
>
> **이중 라우팅 경계 (전역 OMC 플러그인 병존 시)**: OMC keyword-detector 의 워크플로 모드(ralph·autopilot·ulw·team 등)는
> OMC 소유 — 본 하네스는 관여하지 않는다. 반대로 도메인·절차 규율(`*-rules`·`tdd-discipline`·`debugging-discipline`·
> `verify-before-done`)은 **본 하네스가 정본** — OMC 모드 키워드(tdd·analyze·code-review·security-review)와 겹치면
> 프로젝트 스킬의 게이트 수치·절차가 우선한다(지침 우선순위: 프로젝트 CLAUDE.md/HARNESS.md > 플러그인 주입).
> OMC skill-injector 는 omc-learned 디렉토리만 스캔하므로 `.claude/skills/**` 와 충돌하지 않는다(2026-07-22 실사).

## 강제 지점 (하네스가 실제로 개입하는 순간)

문서 규율이 아니라 **훅으로 배선된 실행 지점**이 정본이다. 배선은 `.claude/settings.json` · `scripts/harness/hooks/pre-commit` ·
`.github/workflows/harness-guard.yml` 세 곳에만 존재한다.

> | 시점                     | 트리거                                     | 실행                                                                                                                                                             | 실패 시                                                            |
> | ------------------------ | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
> | 파일 편집 직전           | PreToolUse `Write\|Edit\|MultiEdit`        | `guard.mjs --hook`                                                                                                                                               | **exit 2 = 편집 차단**                                             |
> | Bash 명령 실행 직전      | PreToolUse `Bash`                          | `guard.mjs --hook-bash`                                                                                                                                          | **exit 2 = 명령 차단** (BLOCK) / WARN 은 additionalContext 만      |
> | 파일 편집·스킬 호출 직전 | PreToolUse `Write\|Edit\|MultiEdit\|Skill` | `skill-router.mjs --hook`                                                                                                                                        | 차단 없음(항상 exit 0) — 스킬 로드 리마인더 주입                   |
> | 세션 시작                | SessionStart                               | `telemetry-report.mjs --hook`                                                                                                                                    | 차단 없음 — 최근 차단·라우터 순응률 요약 주입(알릴 것 없으면 침묵) |
> | `git commit`             | `core.hooksPath=scripts/harness/hooks`     | `guard.mjs --staged` (내용 스캔 + 하네스 경로 삭제 검사)                                                                                                         | **커밋 거부** (`--no-verify` 우회 금지)                            |
> | PR·push (develop/main)   | `harness-guard.yml`                        | 하네스 자기 테스트 → `guard.mjs --list` → `guard.mjs --deleted-list` → `harness-audit.mjs` → manifest 추적 검증 → 지식 manifest → 설정 고아 감사 → 워킹트리 청결 | **CI 실패** (로컬 훅 미설치·우회를 재차단)                         |
>
> 삭제는 내용 스캔으로 잡히지 않는다 — 스테이징·CI 파일 목록이 `--diff-filter=ACMR` 로 삭제(D)를 빼고 오기 때문이다.
> 그래서 `--staged`/`--deleted-list` 가 삭제 목록을 따로 받아 하네스 경로를 지키고, 실시간 훅(PreToolUse)은
> Write/Edit 만 보므로 삭제 축은 커밋·CI 2중이 담당한다.
>
> 훅 설치는 `node scripts/harness/install-hooks.mjs` — 미설치 시 로컬 커밋 가드만 비고, 실시간 훅과 CI 는 그대로 산다(3중 구성의 목적).

**guard.mjs 규칙 인벤토리** (전부 차단성 — 발화는 텔레메트리에 규칙 ID 로 적재되어 죽은 규칙을 식별할 수 있다):
`MONEY-PRIMITIVE` · `MONEY-BIGDECIMAL-DOUBLE`(금액 double/float·`new BigDecimal(더블 리터럴)`) ·
`IMMUTABLE-HISTORY` · `MSA-BOUNDARY`(settlement→order import, `import static` 포함) · `ACCOUNT-CONSUME-ONLY` ·
`MARKET-NO-VALUATION` · `OO-DOMAIN-SETTER` · `OO-DOMAIN-MUTABLE-LOMBOK` · `OO-DOMAIN-GENERIC-IAE` ·
`INVALID-ALLOWANCE`(예외 주석은 reason·issue·owner·미래 expires 필수 — 무기한 면제 금지) ·
`HARNESS-DELETE`(`.claude/`·`.codex/`·`scripts/harness/`·`docs/ax/` 삭제 — 1건도 차단, 재생성 가능한
`scratch`·`agent-memory`·`worktrees`·`harness` 는 예외. 의도한 삭제는 `HARNESS_ALLOW_DELETE=1`) ·
`KAFKA-DLQ`(`@KafkaListener` 를 가진 모듈은 DLT 배선이 닿아야 한다 — ⓐ 루트 `github.lms.lemuel` 컴포넌트
스캔 **+ shared-common 의존**, ⓑ 명시 `@Import(KafkaConsumerErrorHandlingConfig)`, ⓒ 자체
`DeadLetterPublishingRecoverer` 배선(폴리글랏 standalone) 중 하나. 폴리글랏도 대상 — `settings.gradle.kts`
밖이라고 유실이 허용되지 않는다. 안 닿으면 Spring Kafka 기본 `FixedBackOff(0, 9)` 로 떨어져 재시도 소진
메시지를 조용히 skip = 사실상 유실) ·
`KAFKA-GROUP-OWNER`(컨슈머 group-id 는 모듈 소유여야 한다 — 두 서비스가 같은 group-id 를 쓰면 카프카가
한 그룹으로 보고 파티션을 나눠 줘 한쪽이 가져간 메시지가 다른 쪽에 오지 않고 오프셋까지 공유돼 **조용히
유실**된다. 예외도 로그도 없다. 2026-08-14 실사건: order-service 가 모놀리스 분리 잔재로
`lemuel-settlement` 그룹을 들고 있었다 — 리스너가 하나 붙는 순간 settlement 파티션을 점유·커밋한다) ·
`WORKFLOW-EMPTY-EXPR`(`.github/workflows/*.yml` 에 빈 표현식 금지 — Actions 는 워크플로 전체를 표현식
렉서로 훑으므로 `run`/`script` 블록 **안의 주석**에 있어도 `An expression was expected` 로 파일이 통째로
무효가 된다. 그 워크플로는 잡 0개·로그 없음·실행 이름이 파일 경로로 뜨는 형태로 죽고, 다른 체크는 초록이라
드러나지 않는다. YAML 파서·공식 워크플로 스키마·액션 SHA 실재 검증이 전부 통과하는 사각지대라 grep 계층에
둔다 — 2026-08 pr-review.yml 이 이 한 줄 주석 때문에 며칠간 죽어 있었다).

**Bash 명령 계층(COMMAND_RULES, `--hook-bash`)** — 2026-08-15 저장소 네이티브로 신설(종전 `check-command` 는
settlement-copilot **플러그인 소유**라 플러그인 미설치 환경에 없었다 — "플러그인 독립" 전제의 구멍이었다):
`CMD-EDIT-BYPASS`(sed -i·perl -i·리다이렉트·tee 로 소스 편집 — 실시간 내용 스캔 우회 봉쇄, Write/Edit 도구가 정답) ·
`CMD-NO-VERIFY`(git commit/push `--no-verify`) · `CMD-PROD-DB-WRITE`(psql/pgcli/pg_dump 쓰기 + kubectl exec DB 접속) ·
`CMD-EVENT-PRODUCE`(lemuel.* 토픽 직접 produce — WARN 비차단). 의도적 실행은 `HARNESS_ALLOW_CMD=1` opt-in.
이 계층은 fail-open(운반 수단 차단이라 입력 파싱 실패가 모든 Bash 를 멈추면 안 된다) — 우회 시도는 커밋·CI 가 내용 기준 재차단.

**skill-router.mjs 라우트 표** (경로 → 주입 스킬, 세션당 스킬별 1회 · 최대 3개): 16개 서비스 디렉토리 전부 → 각 `{서비스}-rules`
(위 "커버리지 완결" 참조)
(settlement `ledger` 경로·account 는 `ledger-invariants` 동반) · `outbox/`·`adapter/in/kafka/`·`adapter/out/event/` →
`idempotency-and-events` · settlement `readmodel|projection` → `projection-view-ops` · `contracts/events/` →
`event-contract-change` · `.claude/hookify.*.local.md` → `hookify-to-guard` · 그 외 `src/{main,test}/` 첫 편집 → `tdd-discipline`.
라우팅 맵 표의 "만지면 로드"와 이 표가 **같은 사실의 두 표현**이므로, 한쪽을 바꾸면 다른 쪽도 바꾼다(`skill-router.test.mjs` 가 회귀 방지).

## 도구 접근 (MCP + 플러그인 독립 이중 경로)

운영/정합 데이터 접근은 **운영 DB 직접 접속 금지** — 아래 두 경로 중 하나만 쓴다. MCP 미설치(CI·새 클론·Codex)에서도 하네스가 죽지 않도록 **저장소 네이티브 경로를 항상 병존**시킨다.

- **경로 A — MCP(리치, 플러그인 설치 시)**: settlement/invest-copilot MCP 도구(`recon_run`·`ledger_entries`·`projection_status`·`outbox_status`·`integrity_check`·`trial_balance` 등). 대화형 조사에 최적.
- **경로 B — 저장소 네이티브(플러그인 0 의존, CI 가능)**:
  - `node scripts/harness/harness-audit.mjs` — 하네스 자기 진단(라우팅 dangling·가드 훅 경로 실존·모듈 로스터·인벤토리)
  - `node scripts/harness/guard.mjs --staged` — 돈/경계/이력 불변식 가드
  - `node scripts/harness/telemetry-report.mjs` — 가드 발화·스킬 사용/제안 텔레메트리 + 가드 카나리아(`.claude/harness/logs`).
    CI 러너분 합산은 `telemetry-ci-pull.mjs` 수집 후 `--merge .claude/harness/ci-logs`
  - `node scripts/harness/report-freshness.mjs <module>` — 게이트 리포트 인용 전 신선도 판정(낡은 XML 인용 차단)
  - `node scripts/harness/session-metrics.mjs` — OMC 세션·미션 완주율·재작업률 KPI 리포트(`.omc` 읽기 전용 관측 — KPI 정본은 `docs/ax/omc-harness.md` — 로컬 전용, 저장소 미포함)
  - `./gradlew :<module>:test`·`:jacocoTestCoverageVerification` — 정합 검증(측정 정답)
  - 서비스 자체 `/admin/integrity`·`/api/account/trial-balance` 조회 API(읽기 전용)
- **불변식**: psql/pg_dump/kafka produce 로 운영 데이터에 직접 손대는 명령을 만들지 않는다(저장소 네이티브
  `guard.mjs --hook-bash` COMMAND_RULES 가 실시간 차단 — 플러그인 check-command 는 설치 시 2차 레이어로 병존).

**MCP 도구 ↔ 플러그인 독립 폴백 매핑** (조용한 "MCP 단독" 금지 — 모든 능력에 폴백 또는 런타임 경계 명시):

> | MCP 도구(경로 A)                                   | 폴백(경로 B)                                                                                                                                                     | 종류             |
> | -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------- |
> | `integrity_check`                                  | 서비스 `/admin/integrity` API                                                                                                                                    | 정적/API         |
> | `trial_balance`·`ledger_entries`                   | `/api/account/trial-balance`·`/api/ledger` (ADMIN) + 🤖`gl-ledger-auditor` 코드 감사                                                                             | API/정적         |
> | `recon_run`·`order_recon_totals`                   | `/internal/recon` + ⌘`/recon-check` 절차                                                                                                                         | API              |
> | (하네스 정합)                                      | `harness-audit.mjs`·`guard.mjs`                                                                                                                                  | **정적, 0 의존** |
> | `projection_status`·`outbox_status`·`stuck_states` | **런타임 전용** — 폴백 없음(라이브 컨슈머 lag/적체는 실행 중 시스템 필요). MCP 미설치 시 Prometheus/Actuator 직접 조회로 대체, 코드 정합은 정적 경로로 분리 검증 |
>
> 정적·계약·정합은 경로 B 로 **CI/오프라인 검증 가능**, 라이브 상태값만 런타임 전용으로 격리된다(이것이 남은 경계 — 코드가 아니라 실행 중 시스템의 속성이므로 하네스로 제거 불가).

## 검증 게이트 (ground truth — 모델 주장이 아니라 기계 판정)

- **ArchUnit** — 헥사고날 경계·서비스 간 의존 방향
- **JaCoCo** — CI LINE 90% / 핵심 도메인 INSTRUCTION 80% (측정은 게이트 태스크가 정답)
- **이벤트 계약 테스트** — cross-service 토픽 스키마 드리프트 빌드 시점 차단 (ADR 0024). 계약 토픽 수는 여기 복제하지 않는다 —
  정본은 `shared-common/src/testFixtures/resources/contracts/events/` (`git ls-files 'shared-common/src/testFixtures/resources/contracts/events/*.schema.json' | wc -l`)
- **돈 경로 가드(저장소 추적)** — `scripts/harness/guard.mjs`: 실시간 PreToolUse(exit 2 차단) + git pre-commit(`core.hooksPath`, `node scripts/harness/install-hooks.mjs`) 이중. 플러그인 독립 — BigDecimal·이력불변·MSA 경계·account 발행금지·market 밸류에이션 + **OO 구조(도메인 public setter·@Setter/@Data·금융 5서비스 generic IAE)** 위반 차단. `--no-verify` 우회 금지. copilot 플러그인 가드가 있으면 2차 레이어로 병존.
  머니 규칙은 선언·파라미터·반환타입·배열·캐스트·var 더블 리터럴 추론과 `new BigDecimal(더블 리터럴)` 생성자까지 커버하고,
  **라인+파일(멀티라인) 이중 스캔**으로 개행 분할 우회를 차단한다. `--staged` 는 돈 경로 프로덕션 변경이 테스트 변경 없이
  스테이지되면 **비차단 DoD 넛지**를 stderr 로 출력한다(차단 안 함 — 완료 판정은 JaCoCo 게이트가 정답, 발화는 텔레메트리 적재).
- **OO 구조 게이트** — `scripts/harness/test/oo-gate.test.mjs`: 트리 전수 스캔(도메인 setter 0·@Setter 0·금융 5서비스 IAE 0·코어 애그리거트 17종 생성자 봉인·상태 enum 9종 canTransitionTo 전이표 보유). 2026-07-14 OO 캠페인(패널 중앙값 9.5+)의 구조 정본 회귀 방지 — CI 하네스 테스트에 자동 포함. 점수 재채점(LLM 판정)은 📘`oo-score` 스킬.
- **AOP 프록시 게이트** — `scripts/harness/test/aop-proxy-gate.test.mjs`: 같은 빈 안에서 `this.method()` 로
  부가기능 메서드를 부르면 프록시를 거치지 않아 `@Retry`·`@CircuitBreaker`·`@Cacheable`·`@Async`·`@PreAuthorize`
  가 **조용히 무력화**된다(컴파일·테스트 통과, 운영에서만 안 걸림). 리포 전수 소스 스캔 — ArchUnit 1.3 이
  Java 25 바이트코드를 못 읽어 소스 계층에 둔다.
- **트랜잭션 롤백 게이트** — `scripts/harness/test/tx-rollback-gate.test.mjs`: 스프링 트랜잭션 AOP 는 언체크만
  롤백하고 **체크 예외는 커밋**한다. 금융 도메인에서 이 기본값은 조용한 사고(반쪽 커밋)가 되므로
  `@Transactional` + 체크 예외 조합에 `rollbackFor` 를 강제한다.
- **Kafka 토픽 카탈로그 게이트** — `scripts/harness/test/kafka-topic-gate.test.mjs`(ADR 0035): 막는 것은
  "파티션 수가 코드 밖에서 정해지는 상태". 메시지 키가 outbox `aggregateId` 라 파티션 수 변경 =
  키 재해시 = 이미 쌓인 메시지까지 **순서 보장 소급 붕괴**(되돌릴 수 없다). 새 토픽은 카탈로그 등록 필수.
- **Kafka 발행부↔카탈로그 게이트** — `scripts/harness/test/kafka-publisher-gate.test.mjs`: 위 게이트는
  `application.yml` 의 `app.kafka.topic.*` 만 보므로 **발행 전용 토픽**(구독 설정이 없어 yml 에 안 적힘)이
  카탈로그에서 통째로 샌다. 실제로 두 번 났다(insurance general_payout 2종 누락 · card statement 계약
  파일명을 옮겨 적어 실재하지 않는 토픽 등재). 발행 코드를 정본으로 카탈로그를 대조한다.
- **SSE nginx 배선 게이트** — `scripts/harness/test/sse-nginx-gate.test.mjs`: 게이트웨이에 SSE 를 열고 nginx 를
  안 고치면 요청이 일반 `api` location 으로 떨어져 `proxy_buffering on` + `read_timeout 60s` 를 받는다 —
  실시간이 아니게 되고 유휴 연결이 60초에 끊긴다. 배선 누락을 빌드 시점에 잡는다(정본 `docs/sse.md`).
- **스케줄러 락 이름 유일성** — `scripts/harness/test/scheduler-lock-gate.test.mjs`: 같은 `@SchedulerLock`
  이름을 두 배치가 쓰면 락 보유 기간 동안 나머지가 **조용히 스킵**된다(예외·로그 없음 → 컴파일도 CI 도 못 잡음).
  리포 전수로 잠근다. 의도적 공유가 필요하면 게이트의 `ALLOWED_SHARED` 에 근거와 함께 등록.
- **커버리지 측정 범위 게이트** — `scripts/harness/test/coverage-scope-gate.test.mjs` + 루트/`shared-common`
  `build.gradle.kts` 의 런타임 스모크: JaCoCo 검증은 **측정 대상 클래스가 0개면 만들 위반이 없어 통과**한다
  (커버리지가 높아서가 아니라 잰 게 없어서다 — 빌드는 초록이고 리포트 파일도 생겨서 컴파일도 CI 도 못 잡는다).
  2026-08-19 deposit·board(리포트+검증)·education(검증)이 실제로 이 상태였다: 아티팩트 XML 클래스 0개,
  HTML `No class files specified`, 임계값을 1.00 으로 올려도 BUILD SUCCESSFUL. 원인은 루트가 이미 교체한
  `classDirectories` 위에 모듈이 같은 관용구를 다시 얹은 것 — `classDirectories.files` 가 **설정 시점에 즉시
  평가**돼 `build/classes` 가 없는 클린 빌드(=CI 의 `clean :module:build`)에서 빈 집합이 스냅샷된다.
  정적(모듈 빌드 스크립트의 `classDirectories` 재정의 금지)과 런타임(대상 0개면 빌드 FAIL) 두 겹으로 막고,
  런타임 스모크가 지워지는 것까지 정적 검사가 감시한다.
- **메뉴↔라우트 정합 게이트** — `scripts/harness/test/menu-route-gate.test.mjs`: 네비게이션 트리의 세 사본
  (시드 SQL `V20260813100000__menu_area_permission.sql` = 정본 · 프론트 폴백 `menuFallback.ts` · `App.tsx` 라우트)을
  대조한다. 메뉴만 있고 라우트가 없으면 **죽은 링크**, 라우트만 있고 메뉴가 없으면 **유령 화면**이 되는데 둘 다
  컴파일러도 런타임도 알려 주지 않는다. 진입점이 네비게이션이 아닌 화면(PG 콜백·인쇄·리다이렉트 등)은
  게이트의 `ROUTES_WITHOUT_MENU` 에 **사유와 함께** 등록해야 통과한다 → 화면 추가 시 "메뉴에 넣을지"를
  강제로 결정하게 만든다. 삭제된 사이드바 셸 3종(SettlementLayout·CeoLayout·SystemLayout)의 부활도 함께 막는다.
- **백엔드 표면↔화면 커버리지 게이트** — `scripts/harness/test/api-screen-gate.test.mjs`: 자바 16서비스의
  `@RestController` base path 를 프론트 전체(`frontend/src`, 테스트 제외)의 URL 리터럴과 대조한다. 메뉴↔라우트
  게이트가 못 보는 반대편 누락 — **기능은 짰는데 부르는 화면이 없는 상태**를 잡는다(실제로 card Phase 2·insurance·
  deposit·organization 이 REST·게이트웨이 라우팅을 다 갖춘 채 화면 0 으로 방치됐다). 새 컨트롤러는 ① 화면을 붙이거나
  ② `MACHINE_ONLY`(웹훅·VAN 단말·내부키 수집 트리거·일회성 백필) ③ `SCREEN_PENDING`(인정된 화면 부채) 중 하나로
  **사유와 함께** 분류해야 통과한다. 부채는 `PENDING_BUDGET` 래칫으로 **내려가기만** 한다 — 줄었는데 예산을 안 내려도
  FAIL 이라 목록이 늘 정확하다. 추출 정규식이 깨져 전부 통과하는 가짜 GREEN 도 스캔 하한선으로 막는다.
- **프론트 테스트 렌더 경합 게이트** — `scripts/harness/test/async-query-gate.test.mjs`: `waitFor(API 가 불렸는지)`로
  기다린 뒤 곧바로 `screen.getBy*` 로 데이터 의존 엘리먼트를 집는 형태를 막는다. 호출된 시점과 렌더에 반영된 시점
  사이에 상태 갱신 한 틱이 있어 **로컬에선 늘 통과하고 CI 러너에서만 랜덤하게 실패**한다 — 2026-08-13 하루에 두
  파일(차지백 콘솔·카테고리 정합 패널)이 같은 이유로 필수 체크를 깼고, 매번 PR 이 막힌 뒤에야 발견됐다. 고치는 법은
  `await screen.findBy*`(재시도 조회)이며, 마운트부터 있는 정적 chrome(헤더·필터 탭·조건 없는 폼)은 게이트의
  `STATIC_QUERIES` 에 **사유와 함께** 등록해야 통과한다. 린트로는 못 잡는다 — `testing-library/prefer-find-by` 는
  `waitFor(() => getBy...)` 형태만 보고 이 사각지대는 대상이 아니다. 도입 시점에 이미 새 파일 1건을 잡았다.
- **하네스 자기 진단** — `scripts/harness/harness-audit.mjs`: 문서 드리프트를 규율이 아닌 **기계 게이트**로 승격(과거 문서 3주 방치 재발 방지).
  라우팅 맵 dangling 도 기계 검증한다 — 🤖📘⌘ 아이콘 줄의 backtick 진입점 토큰을 agents/skills/commands 실존과 대조
  (에이전트·스킬·커맨드를 삭제/개명하고 라우팅 맵을 안 고치면 audit FAIL → CI 차단).
  **문서 사실 게이트 5종**(상태 기술 문서 한정): 이벤트 계약 토픽 수 · 구현 상태 역전(어댑터 실재 vs "미구현") ·
  소비처 배선("소비처 미배선" vs 실제 참조) · Spring Boot 버전 드리프트(정본은 `build.gradle.kts` — 문서가
  같은 메이저의 다른 패치 버전을 말하면 FAIL) · **서비스 수**(2026-08-15 추가 — `N 마이크로서비스`/`N개 서비스`
  주장을 `settings.gradle.kts` 로스터(gateway 제외)와 대조). 모듈 트리 대조는 트리 표기만 보므로 산문 주장이
  새는 축이 따로 있었다 — HARNESS.md 자신이 3주간 14 로 남아 있었고 같은 문서 안의 "자바 16서비스" 와
  모순이었다. 로스터 앵커(`API Gateway`·`gateway`·`DB-per-service`)가 같은 줄에 있을 때만 주장으로 인정해
  부분집합("금융 5서비스")·폴리글랏 합계(24)를 오탐하지 않는다.
- **리포트 신선도 게이트** — `scripts/harness/report-freshness.mjs <module>...`: "가짜 GREEN 4경로" 중
  **UP-TO-DATE 낡은 XML 인용** 축의 기계화. 테스트/JaCoCo XML 이 그 모듈 `src/` 최신 mtime 보다 오래됐으면
  STALE(exit 1) — 직전 빌드 산출물을 이번 변경의 증거로 인용하는 것을 종료 코드로 차단한다. 리포트가
  아예 없으면 MISSING(미실행 — "통과" 주장 불가). 지금까지 "인용 전 mtime 확인"은 운용 지식이었다 —
  게이트 결과를 인용하기 전에 이 명령을 먼저 돌린다.
- **CI 판정 조회** — `scripts/harness/ci-verdict.mjs [sha|ref]` (+ 게이트 `test/ci-verdict-gate.test.mjs`):
  "가짜 GREEN" 의 또 한 경로인 **취소된 실행이 통과로 읽히는 상태**를 종료 코드로 가른다. develop 의 ci·harness-guard 는
  `cancel-in-progress` 라 연속 push 중간 커밋의 실행이 `cancelled` 로 끝나는데, `cancelled` 는 `failure` 가 아니라
  브랜치에도 `gh run list` 에도 빨간 X 가 남지 않는다 — 판정이 **없는** 것과 판정이 **통과** 인 것이 같은 색이다.
  여기에 경로 필터가 겹치면 구멍이 영구화된다: `Frontend - Tests` 는 `frontend == 'true'` 일 때만 돌고 push 의 변경
  감지 기준은 **직전 커밋**이라, 프론트를 바꾼 커밋의 실행이 취소되면 뒤 커밋들은 그 잡을 `skipped` 로 넘겨 그 변경은
  영영 테스트되지 않는다(2026-08-19 실측: 커밋 `1d17aaa7d` — 잡 단위 재실행마저 다시 취소됐고, 판정은 상시 열려 있던
  릴리스 PR 실행에서 우연히 메워졌다. PR 이 닫혀 있었다면 그대로 미판정). 그래서 `success`/`failure` 만 결론으로 세고
  `cancelled`·`skipped`·진행중은 결론이 아니며, 판정을 대상 커밋 → 후손 → **조상 + 해당 경로 무변경 증명** 순으로 찾아
  `PASS`/`COVERED`/`PENDING`/`UNJUDGED`/`FAIL` 로 가른다. 게이트는 판정 규칙과 함께 **필수 체크 표의 드리프트**를
  막는다 — 체크 이름·경로 조건을 `ci.yml`/`harness-guard.yml`/`semgrep.yml` 원문 및 CLAUDE.md 목록과 대조하므로,
  잡 이름이나 `if:` 를 바꾸면 조용히 없는 체크를 조회하는 대신 CI 가 FAIL 한다. 읽기 전용이다 — 재실행은 하지 않고
  필요한 `gh` 명령만 출력한다(잡 단위 재실행은 경로 필터·concurrency 에 다시 걸리므로 실행 전체를 다시 돌려야 한다).
- **로컬 통합 검증** — `scripts/verify.sh`: CI(`harness-guard.yml` + `ci.yml`)의 판정을 **같은 순서로** 로컬에서 재현한다.
  하네스 테스트 → 자기 진단 → 변경 파일 가드 → 삭제 가드 → 변경 모듈 Gradle. "다 됐다" 를 자기보고가 아니라 종료 코드로 증명하는 지점.
  `--fast`(Gradle 생략, 수초) · `--all`(전체 build) · `--base <ref>`. 느려지면 우회당하므로 기본 경로는 변경 모듈만 빌드한다(ci.yml 매핑과 동일).
- **하네스 개선 로그** — `docs/plan/HARNESS-IMPROVEMENT-LOG.md`: 하네스를 고칠 때마다 `status`·`predicted_effect`·`verified_at` 을 남긴다.
  규칙을 늘리기만 하고 효과를 잰 적이 없어 아무도 지우지 못하던 문제에 대한 대응 — 예측이 빗나가면 `reverted` 로 남기고 되돌린다.
- **CI 강제** — `.github/workflows/harness-guard.yml`: PR/푸시마다 변경 파일 가드(`guard.mjs --list`) + 자기 진단을 **로컬 설정과 무관하게** 실행(훅 미설치·`--no-verify` 우회를 CI가 재차단). 기존 `ci.yml`(빌드·테스트·커버리지)와 병존.
- **하네스 텔레메트리(관측 계층)** — `scripts/harness/telemetry.mjs`: 가드 차단·스킬 사용·라우터 제안을 `.claude/harness/logs/*.jsonl`(gitignore, 비커밋 — `.omc` 는 OMC 플러그인 소유·정리 대상이라 하네스 런타임은 프로젝트 소유 `.claude/harness/` 에 격리)에 append-only 적재. 집계는 `node scripts/harness/telemetry-report.mjs`(규칙별 발화 횟수·0회=죽은 규칙 후보·스킬 사용률·제안 대비 미로드). 관측 실패가 가드를 깨뜨리지 않는 non-fatal 설계, 킬 스위치 `HARNESS_TELEMETRY=off`.
  **머신 경계 봉합(2026-08-15)**: CI 러너의 이력은 아티팩트(`harness-telemetry-*`, 30일)로만 남아 로컬
  리포트와 단절돼 있었다 — `node scripts/harness/telemetry-ci-pull.mjs` 가 gh CLI 로 최근 run 아티팩트를
  `.claude/harness/ci-logs/<run_id>/` 에 멱등 수집하고, `telemetry-report.mjs --merge .claude/harness/ci-logs`
  가 로컬+CI 를 합산 집계한다(수집 실패는 best-effort — 관측 도구는 게이트를 막지 않는다).
  **닫힌 피드백 루프**: SessionStart 훅이 `telemetry-report.mjs --hook` 으로 압축 요약(최근 차단·라우터 순응률·카나리아 생존)을
  세션마다 additionalContext 로 자동 주입 — 사람이 리포트를 돌리지 않아도 관측이 에이전트에게 도달한다(알릴 것 없으면 침묵).
- **스킬 라우터(권장의 기계화)** — `scripts/harness/skill-router.mjs`: PreToolUse 훅(Write/Edit/MultiEdit/Skill)이 편집 대상 경로를 보고 해당 `*-rules`·횡단 스킬 로드를 additionalContext 리마인더로 주입(세션당 스킬별 1회). 라우팅 맵의 "만지면 로드" 규칙을 문서 규율에서 기계 리마인더로 승격 — 가드가 금지를 강제한다면 라우터는 권장을 주입한다. 절대 차단하지 않음(항상 exit 0).

## 하드스톱 — 절대 금지 (위반 = 회계·아키텍처 손상 · 정본은 CLAUDE `🚫 핵심 가드레일`)

- 금액에 `double`/`float` 금지 → `BigDecimal` 만 · `POSTED` 전표 수정 금지 → 역분개만 · 반쪽 전표 금지 → 차1·대1 균형 팩토리만
- `settlement`→`order` import·cross-DB 조인 금지 · 도메인→어댑터 import 금지 · account 이벤트 발행 금지 · market PER/PBR 계산 금지
- 셀러 식별자를 요청 파라미터로 신뢰(IDOR) 금지 → JWT 주체 파생·소유권 대조 · `main` 직접 push 금지
  > 위는 압축 신호(요약). 전체 근거·서비스별 강제 규칙은 CLAUDE 🚫 섹션과 `*-rules` 스킬이 정본. 기계 차단은 ArchUnit·돈경로 가드가 담당.

## 완료 판정(DoD) — 선언 전 이 게이트를 통과했는가 (LLM 판단 아님, 기계가 정답)

- [ ] `./gradlew :<module>:test` 통과 (관련 모듈 전부)
- [ ] `:<module>:jacocoTestCoverageVerification` — CI LINE 90% / 핵심 도메인 INSTRUCTION 80% 통과
- [ ] **MSA 경계 변경 시** ArchUnit 위반 0 (`settlement`↔`order` 코드·cross-DB 의존 0 확인)
- [ ] **cross-service 토픽 변경 시** 이벤트 계약 테스트(ADR 0024) 통과 — 프로듀서·컨슈머 양방향
- [ ] 돈 경로 가드 통과 · `--no-verify` 미사용
- [ ] **작성과 검증 분리** — 같은 컨텍스트 자기 승인 금지, `code-reviewer`/`verifier` 별도 패스로 증거 수집
- [ ] 문서에 휘발성 수치를 적었으면 재현 명령을 병기했는지 확인(값만 적으면 즉시 드리프트)
  > 하나라도 미충족이면 "완료"라고 쓰지 않는다. 커밋은 `develop` 항목별 개별 커밋(PowerShell 은 `git commit -F <file>`).
  > `main` 반영은 PR·**squash 만**·필수 CI 6종 — 목록은 CLAUDE.md (직접 push 금지 — 보호 브랜치). 운영 배포는 강한 `JWT_SECRET`·`internal-key-required=true`·외부 API 키 주입 확인.

## 드리프트 방지 규약 (문서 최신성)

- **휘발성 수치**(마이그레이션·테스트·서비스 수 등)는 값만 적지 말고 **재현 git 명령을 병기**한다 → 수치가 falsifiable 해져 조용한 드리프트가 불가능해진다. 별도 수치 정본 문서는 두지 않는다 — 병행 세션이 동시에 갱신하면서 값이 늘 어긋났다(2026-08-07 STATUS.md 폐지).
- 수치 집계는 반드시 **git-tracked 소스 기준**(`git ls-files`) — `find` 는 `build/` 사본과 `.claude/worktrees/` 에이전트 사본을 이중 집계하므로 금지(과거 마이그레이션 224 유령 수치의 원인).
- 문서 상호참조는 **단일 출처**를 가리킨다: 수치→재현 명령, 기능·API→SPEC, 규칙→`*-rules` 스킬, 경계·컨벤션→CLAUDE. 같은 사실을 두 곳에 복제하지 않는다.

**셀프체크** (수치 드리프트 + 라우팅 맵 dangling 진입점을 한 번에 노출 — 하네스 수정 후 실행):

```bash
# 1) 휘발성 수치는 필요할 때 직접 센다(문서에 박제하지 않는다)
git ls-files '*/src/main/resources/db/migration/*.sql' | wc -l
git ls-files '*/src/test/*Test.java' '*/src/test/*Tests.java' '*/src/test/*IT.java' | wc -l
# 2) 라우팅 맵 진입점 존재 검증 — harness-audit.mjs 가 기계 검증(수동 grep 불필요, CI 자동 포함)
node scripts/harness/harness-audit.mjs
```

## 확장 가이드 (하네스를 늘릴 때)

- 새 도메인 전용 에이전트·스킬을 만들 땐 관련 **하드스톱 + `*-rules`** 를 프롬프트에 내재화(위 하드스톱 섹션이 정본).
- 돈 경로(결제·환불·지급·대출·투자) 신규 코드는 멱등(Idempotency-Key)·동시성(비관락)·실패 롤백을 점검 항목에 포함.
- 새 서비스 추가 시 `{서비스}-rules` 스킬 + 커맨드 + gateway/스캔 배선(5곳, 절차 정본: 📘`msa-service-wiring`)을 함께 배선하고, 라우팅 맵에 트리거 행 1개 추가 후 **셀프체크** 재실행.

## 관련 문서

- `CLAUDE.md` — 에이전트 운용 규칙 / 아키텍처 경계·컨벤션
- `SPEC.md` — 전체 기능명세(엔드포인트·도메인 규칙·이벤트 카탈로그)
- `docs/PORTFOLIO.md` — 면접용 1장 요약 · `README.md` — 아키텍처 개요
- `docs/adr/` — 아키텍처 결정 기록
