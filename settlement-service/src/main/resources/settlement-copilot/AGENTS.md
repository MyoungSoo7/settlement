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
  (`recon_run`, `ledger_entries`, `projection_status`, `outbox_status`, `pg_recon_runs`,
  `integrity_check`, `ledger_completeness`, `payout_recon`, `holdback_status`, `stuck_states`,
  `refund_adjustments`, `event_accounting`)로만 한다.

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
