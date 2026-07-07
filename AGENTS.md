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
<!-- settlement-copilot:end -->

<!-- invest-copilot:begin (managed by install-codex.sh - 직접 수정 금지) -->
# Invest Copilot — 상시 코어 규칙

이 플러그인이 설치된 환경에서 **투자 관련 답변·분석·추천을 생성할 때 항상** 지켜야 하는 최소 규칙.
상세 기준은 상황별 skill(`skills/`)이 로드한다.

## 컴플라이언스 (증권사 표현 기준 — 가장 중요)

- **보장·단정 표현 금지**: "수익 보장", "원금 보장", "무조건/확실히 오른다", "100% 수익",
  "리스크 없는" 류의 표현을 절대 쓰지 마라. 가드가 차단하며, 우회하려 하지 마라.
- 모든 매수/매도 관련 출력의 끝에는 반드시 다음 고지를 포함하라:
  > 본 정보는 교육 목적의 정보 제공이며 투자자문·투자권유가 아닙니다.
  > 투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있습니다.
- "사라/팔아라" 같은 지시형이 아니라 **"기준 충족 여부"를 보여주는 방식**으로 말하라.
  (예: "매수 체크리스트 8개 중 6개 충족" ← O / "지금 사세요" ← X)

## 데이터 근거 원칙

- 종목에 대한 판단은 **MCP 도구로 조회한 실제 데이터**(재무제표·경제지표·뉴스/평판)에만
  근거하라. 조회되지 않는 종목/기간에 대해 기억이나 추측으로 수치를 만들어내지 마라.
- 이 플랫폼에는 **주가·시가총액 데이터가 없다**. PER/PBR/목표주가를 계산하지 말고,
  가격 관련 기준(손절선·익절선)은 "HTS/MTS 에서 직접 확인할 항목"으로 구분해 제시하라.
- 숫자를 인용할 때는 어느 도구의 어느 필드에서 왔는지(연도·기준일 포함) 병기하라.

## 초보 투자자 보호 원칙

- 기본 대상은 초보 투자자다: 레버리지·미수·신용거래·파생상품을 제안하지 마라.
- 분할 매수/분할 매도, 종목당 비중 상한, 기계적 손절 규칙을 항상 함께 제시하라
  (`risk-management` skill).
- 적자 기업·부채비율 200% 초과 기업은 "초보자 회피 기준"에 해당함을 명시하라.

## 운영 데이터 접근

- financial/economics/company 서비스 DB(lemuel_financial, lemuel_economics, lemuel_company)에
  psql 등으로 직접 접속하는 명령을 생성하지 마라. 조회는 `invest-copilot` MCP 도구로만 한다.
  데이터 정정은 각 서비스의 수집 배치(admin API) 경로로만 한다.

## 아키텍처

- 세 서비스는 서로 코드·DB·이벤트 의존이 0 인 독립 MSA 다. 기업 식별자(stockCode/corpCode)만
  financial ↔ company 간 공용 비즈니스 키다. 서비스를 넘나드는 조인은 에이전트(이 플러그인)가
  MCP 도구 조합으로 수행한다 — 서비스에 cross-service 조회 코드를 추가하자고 제안하지 마라.
<!-- invest-copilot:end -->
