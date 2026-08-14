<!-- codex-project:begin (project-owned; keep above managed plugin blocks) -->
# Codex 프로젝트 작업 지침 — Lemuel

이 파일은 Codex가 이 저장소에서 작업할 때 적용하는 프로젝트 공통 운영 규칙이다.
아래의 `settlement-copilot` 블록은 설치 스크립트(`settlement-service/src/main/resources/settlement-copilot/install-codex.sh`)가
관리한다 — 내용 수정은 템플릿 원본(`settlement-copilot/AGENTS.md`)과 함께 한다. 로컬 전용 플러그인
(invest-copilot 등)의 블록은 이 추적 파일에 커밋하지 않는다(공개 저장소 제출물 정책).

## 적용 우선순위와 범위

- 시스템·개발자·사용자 지침이 최우선이다.
- 이 루트 `AGENTS.md`는 저장소 전체에 적용한다. 더 깊은 경로의 `AGENTS.md`는 해당 하위 경로에 추가 적용한다.
- `CLAUDE.md`는 프로젝트의 상세 아키텍처·도메인·빌드 정본으로 참조한다. Claude 전용 훅·명령어를 Codex에서 사용할 수 있다고 가정하지 않는다.
- 작업에 해당하는 Codex skill은 행동 전에 `SKILL.md`를 끝까지 읽고 적용한다. 사용한 skill이 요구하는 MCP·검증·질문 절차를 생략하지 않는다.

## Codex 작업 프로토콜

- 작업 시작 전 `git status -sb`, 관련 파일, 최근 변경을 확인한다. 기존 사용자 변경을 되돌리거나 덮어쓰지 않는다.
- 여러 파일·도메인·서비스를 건드리는 작업은 먼저 범위와 검증 계획을 세운다. 독립 작업만 병렬화하고, 공유 파일은 소유권을 나눠 충돌을 피한다.
- 파일 수정은 `apply_patch`를 사용한다. PowerShell here-string, `cat`, 임시 Python 쓰기 스크립트로 파일을 덮어쓰지 않는다.
- 완료·수정·통과를 주장하기 전에 실제 명령을 실행하고 결과와 종료 코드를 확인한다. 테스트·빌드·가드가 실패하면 원인과 미해결 상태를 그대로 보고한다.
- 코드 변경 후에는 가장 가까운 테스트와 저장소 가드를 실행한다. 정산·원장·지급·결제 코드는 금액·이력·멱등성 규칙을 별도 검토한다.
- `git commit --no-verify`로 가드를 우회하지 않는다. 커밋·푸시는 사용자가 명시적으로 요청한 경우에만 수행한다.

## Codex 도구·MCP 규칙

- 도구가 현재 Codex 세션에 노출되지 않았으면 이름을 추측하거나 일반 셸 작업으로 대체하지 않는다. 필요한 deferred MCP schema를 먼저 로드하고, 그래도 unavailable이면 차단 사유를 보고한다.
- Ouroboros 계열 호출은 호출 직전에 해당 도구의 schema를 다시 로드한다. `auto`·`interview`·`seed`·`run`의 MCP 경로와 CLI 경로를 혼동하지 않는다.
- 비동기 job은 반환된 `job_id`·`session_id`·`cursor`를 보존한다. observer가 cursor를 소유하면 주 세션에서 같은 job을 중복 polling하지 않는다.
- MCP·운영 조회 결과를 실제로 확인하지 않고 상태·성공·완료를 추측하지 않는다. 운영 DB에 직접 `psql`, `pg_dump`, Kafka produce 명령을 만들지 않는다.

## 저장소·브랜치 규칙

- 기본 작업 브랜치는 `develop`이며 항목별 커밋을 선호한다. `main`은 보호 브랜치이므로 직접 push하지 않고 PR을 사용한다.
- 커밋 전 변경 범위를 다시 확인한다. 사용자가 “전부 커밋”이라고 명시하지 않은 한 `git add -A`로 unrelated 변경을 묶지 않는다.
- 삭제·추적 해제 전에는 정확한 대상과 로컬 보존 여부를 확인한다. 광범위한 recursive 삭제나 reset은 사용자 승인 없이는 실행하지 않는다.

## 프로젝트 정본

- 금액·정산·원장·이벤트·보안·헥사고날 규칙은 이 파일의 managed copilot 블록과 `CLAUDE.md`를 따른다.
- 기능 상세는 `SPEC.md`, 아키텍처 결정은 `docs/adr/`, 빌드·인프라·실행법은 `docs/DEVELOPMENT.md`를 확인한다.
- 구현 전에 해당 도메인 skill(예: `settlement-domain-rules`, `money-safety`, `idempotency-and-events`)을 선택하고, 완료 전에 검증 skill을 적용한다.

<!-- codex-project:end -->

<!-- settlement-copilot:begin (managed by install-codex.sh - 직접 수정 금지) -->
# Settlement Copilot — 상시 코어 규칙

이 저장소(또는 이 플러그인이 설치된 정산 코드베이스)에서 작업할 때 **항상** 지켜야 하는 최소 규칙.
상세 규칙은 상황별 skill(`skills/`)이 로드한다.

## 돈 (Money)

- 금액 연산은 **BigDecimal** 만 사용한다. `float`/`double`/`Double.parseDouble` 로 금액을 다루는 코드가
  보이면 작성하지 말고, 기존 코드에서 발견하면 반드시 지적하라.
- 나눗셈·비율 연산에는 **RoundingMode 를 명시**한다 (이 코드베이스 표준: `HALF_UP`).
- JSON 직렬화 시 금액은 십진 문자열로 다루고, JS 쪽에서 `Number()` 변환을 제안하지 마라.

## 이력 불변 (Immutable History)

- 정산(`settlements`)·원장(`ledger_entries`)·지급(`payouts`) 레코드는 **UPDATE/DELETE 하지 않는다**.
  정정은 조정(adjustment)/역분개(reversal) 레코드 **추가**로만 한다. (ADR 0004, ADR 0007)
- 스냅샷 컬럼(`settlements.commission_rate` 등)은 생성 후 절대 갱신하지 않는다.
  "요율이 바뀌었으니 과거 정산도 고치자"는 요구는 거부하고 조정 트랜잭션을 제안하라.

## 이벤트·멱등성

- Kafka 컨슈머를 새로 만들면 반드시 `processed_events (consumer_group, event_id)` PK 멱등 체크를 포함하라.
- 이벤트 발행은 직접 `kafkaTemplate.send()` 하지 말고 **Outbox**(`outbox_events` INSERT)를 경유하라. (ADR 0003)

## 운영 데이터 접근

- 운영/스테이징 DB 에 psql 등으로 **직접 접속하는 명령을 생성하지 마라**.
  대사·원장·프로젝션 상태 조회는 `settlement-copilot` MCP 도구
  (`recon_run`, `order_recon_totals`, `ledger_entries`, `projection_status`, `outbox_status`,
  `pg_recon_runs`, `integrity_check`, `ledger_completeness`, `payout_recon`, `holdback_status`,
  `stuck_states`, `refund_adjustments`, `event_accounting`, `settlement_simulate`)로만 한다.

## 가드 자가 검증 (실시간 훅이 없는 환경 — Codex CLI 등)

- 금액 스코프 파일(settlement/ledger/payout/chargeback/loan/payment/recon 경로의 `.java`/`.kt`)을
  쓰거나 수정하기 **전에** MCP `guard_check(file_path, content)` 를 호출해 검사하라.
  `blocked=true` 면 그 내용을 쓰지 말고 violations 메시지의 지시를 따르라.
- DB 클라이언트(psql/pgcli/pg_dump)·kafka produce 계열 명령을 실행하기 전에는
  `guard_check(command)` 로 검사하라.
- 최종 방어선은 git pre-commit 가드다 — 커밋이 차단되면 `--no-verify` 로 우회하지 말고 원인을 고쳐라.

## 민감정보

- 로그에 계좌번호·주민번호·카드번호·실명을 그대로 남기는 코드를 작성하지 마라.
  마스킹 유틸(`shared-common` `common.audit`)을 사용하라.

## 아키텍처

- 헥사고날 규칙: `domain` 은 프레임워크·adapter 를 import 하지 않는다. ArchUnit 테스트가 강제한다.
- settlement ↔ order 는 코드·DB 의존 0 — 연계는 Kafka 이벤트 프로젝션과
  order 내부 API `/internal/recon/*` (헤더 `X-Internal-Api-Key`) 로만 한다. (ADR 0020)
<!-- settlement-copilot:end -->
