# Settlement Copilot — 정산 도메인 특화 Codex 플러그인 설계서

> Lemuel 정산 플랫폼(settlement-service)의 도메인 자산을 기반으로,
> 카카오페이증권과 같은 금융/핀테크 기업의 **정산·대사·원장 엔지니어링 조직**이
> AI 코딩 에이전트(OpenAI Codex CLI, Claude Code 호환)에 꽂아 쓰는 플러그인 설계.

---

## 1. 배경과 문제 정의

정산 도메인은 일반 CRUD 도메인과 달리 AI 에이전트가 실수하면 **돈이 틀어지는** 영역이다.

| 일반 도메인 | 정산 도메인 |
|---|---|
| 버그 = 화면 오류 | 버그 = 셀러 지급액 오류, 회계 불일치 |
| 재시도 = 무해 | 재시도 = 이중 지급 (멱등성 필수) |
| float 연산 허용 | BigDecimal + 라운딩 정책 강제 |
| 로그 자유 | PII·계좌번호 마스킹 의무 (전자금융감독규정) |
| 스키마 변경 자유 | 이력 보존(스냅샷) 원칙 — 과거 정산 재계산 금지 |

범용 AI 에이전트는 이 규칙을 모른다. **Settlement Copilot 플러그인**은
(1) 도메인 지식, (2) 검증 도구, (3) 가드레일 세 층을 에이전트에 주입해
"정산 코드를 아는 시니어가 옆에 앉은 것"과 같은 효과를 낸다.

### 대상 사용자 (페르소나)

| 페르소나 | 니즈 |
|---|---|
| 정산 백엔드 개발자 | 수수료/홀드백/역정산 로직 구현 시 도메인 규칙 자동 준수 |
| 대사(recon) 운영자 | 일 대사 불일치 발생 시 원인 추적을 에이전트에게 위임 |
| 온콜 엔지니어 | 프로젝션 lag·컨슈머 DLT 적체 등 장애를 러너북 기반으로 진단 |
| 컴플라이언스 리뷰어 | PR 의 금액 연산·민감정보 로깅·이력 훼손 여부 자동 스크리닝 |

---

## 2. 아키텍처 개요 — 3-Layer 플러그인

```
┌─────────────────────────────────────────────────────────┐
│  AI Coding Agent (Codex CLI / Claude Code)              │
├─────────────────────────────────────────────────────────┤
│ ① Knowledge Layer  — AGENTS.md + Skills (도메인 규칙)    │
│    상태머신 · 수수료/홀드백 정책 · 멱등 3단 방어 · ADR 요약 │
├─────────────────────────────────────────────────────────┤
│ ② Tool Layer  — MCP Server (읽기 전용 검증 도구)          │
│    recon.compare · ledger.trialBalance · outbox.lag ·    │
│    projection.status · settlement.simulate               │
├─────────────────────────────────────────────────────────┤
│ ③ Guardrail Layer — Hooks (커밋/실행 전 자동 차단)        │
│    금액 float 금지 · PII 로깅 차단 · 이력 컬럼 UPDATE 차단 │
└─────────────────────────────────────────────────────────┘
```

- **①은 정적(파일)**, **②는 동적(API 호출)**, **③은 강제(차단)**.
- ②의 MCP 서버는 운영 DB 에 직접 붙지 않고, 서비스가 이미 노출하는
  **내부 API/메트릭만 프록시**한다 (읽기 전용, RBAC 적용).

---

## 3. Layer ① — Knowledge Layer (Skills)

Lemuel 의 ADR·러너북을 에이전트가 필요할 때만 로드하는 skill 로 패키징한다.
(항상 컨텍스트에 넣지 않고 트리거 시 로드 → 토큰 절약 + 최신성 유지)

### 3.1 Skill 목록

| Skill | 트리거 | 내용 근거 (Lemuel 자산) |
|---|---|---|
| `settlement-domain-rules` | 정산 로직 수정 시 | 상태머신(ADR 0002), 역정산=조정 트랜잭션(ADR 0004), 등급별 수수료 3.5/2.5/2.0% + `commission_rate` 스냅샷, 홀드백(ADR 0015), T+N 주기(ADR 0014) |
| `money-safety` | 금액 연산 코드 작성 시 | BigDecimal + RoundingMode 명시, 통화 단위, 수수료 계산 순서(수수료 차감 → 홀드백 보류), 검증 테스트 템플릿 |
| `idempotency-and-events` | Kafka 컨슈머/발행 코드 시 | Outbox 패턴(ADR 0003), 3단 멱등 방어(event_id UNIQUE → processed_events PK → payment_id UNIQUE), DLT·리플레이(ADR 0017), 스키마 레지스트리(ADR 0022) |
| `ledger-invariants` | 원장 코드/조회 시 | 복식부기 불변식 — 차/대 합계 일치, PENDING→POSTED→REVERSED 만 허용, 삭제 금지·역분개만(ADR 0007) |
| `recon-playbook` | 대사 불일치 조사 시 | 일 대사 절차, `/internal/recon` 합계 비교 방식, 불일치 유형별 원인 트리(누락 이벤트/지연 프로젝션/환불 타이밍) |
| `incident-runbooks` | 온콜/장애 키워드 시 | `settlement-projection-lag`, DB cutover 러너북을 skill 화 — 진단 명령어와 판단 기준 포함 |
| `compliance-review` | PR 리뷰 요청 시 | PII·계좌 마스킹 규칙, 감사로그 필수 지점, 스냅샷 컬럼(수수료율 등) UPDATE 금지 목록 |

### 3.2 AGENTS.md (상시 로드되는 최소 코어)

skill 로 빼기엔 너무 기본적인 것만 남긴다 (≈50줄):

- "금액은 BigDecimal, 라운딩 명시. float/double 이 보이면 무조건 지적하라."
- "정산·원장 레코드는 UPDATE/DELETE 하지 않는다. 정정은 조정(역분개) 레코드 추가로만."
- "컨슈머 코드를 만들면 반드시 processed_events 멱등 체크를 포함하라."
- "운영 데이터 조회는 MCP 도구로만. DB 직접 접속 명령을 생성하지 마라."

---

## 4. Layer ② — Tool Layer (MCP Server)

`settlement-copilot-mcp` — 서비스 내부 API 를 에이전트용으로 감싼 **읽기 전용** MCP 서버.

### 4.1 도구 명세

| MCP Tool | 백엔드 근거 | 입력 → 출력 |
|---|---|---|
| `recon_compare` | order `/internal/recon` + settlement `ReconciliationController` | 날짜 범위 → 양측 합계·건수 diff, 불일치 항목 목록 |
| `ledger_trial_balance` | `LedgerController` | 기간 → 차/대 합계, 불일치 계정 |
| `projection_status` | `SettlementProjectionGauges` (Prometheus) | — → 뷰별 lag(초), 최종 적재 시각 |
| `outbox_lag` | outbox 폴러 메트릭 | — → 미발행 건수, oldest age |
| `dlt_inspect` | DLT 토픽 (ADR 0017) | 토픽 → 실패 이벤트 샘플 + 실패 사유 분포 |
| `settlement_simulate` | 도메인 로직 재사용 (dry-run) | 주문금액·등급·주기 → 수수료/홀드백/지급예정일 계산 결과 |
| `pg_recon_status` | `PgReconciliationController` | 실행일 → RUNNING/COMPLETED/FAILED + 불일치 요약 |
| `event_schema_diff` | 스키마 레지스트리 (ADR 0022) | 토픽·버전 2개 → 필드 diff, 호환성 판정 |

### 4.2 보안 설계 (금융사 요건)

| 항목 | 설계 |
|---|---|
| 권한 | MCP 서버 → 서비스 호출 시 서비스 계정 토큰 + 스코프 `copilot:read` 만 부여. 쓰기 API 는 아예 라우팅하지 않음 |
| 망분리 | MCP 서버는 사내망 배포. 에이전트는 stdio/사내 프록시로만 접속 (외부 인터넷 경유 금지) |
| 데이터 마스킹 | 응답 직전 공통 필터: 계좌번호·주민번호·이름 마스킹. 에이전트 컨텍스트에 원문 PII 가 들어가지 않게 **서버 측에서** 차단 |
| 감사 | 모든 도구 호출을 `who(사번)·when·tool·args` 감사로그로 적재 — 기존 `shared-common.common.audit` 재사용 |
| 금액 정밀도 | 응답은 문자열 십진수로 직렬화 (JSON number 부동소수점 오염 방지) |

---

## 5. Layer ③ — Guardrail Layer (Hooks)

에이전트가 코드를 쓰거나 명령을 실행하기 **직전** 자동 검사하는 훅.

| Hook | 시점 | 규칙 |
|---|---|---|
| `money-type-guard` | 파일 쓰기 전 | 정산/원장/지급 패키지에서 `float`, `double`, `Double.parseDouble` 금액 연산 감지 → 차단 + BigDecimal 안내 |
| `immutable-history-guard` | 파일 쓰기 전 | `commission_rate` 등 스냅샷 컬럼에 대한 `UPDATE` SQL / setter 생성 감지 → 차단 |
| `pii-logging-guard` | 파일 쓰기 전 | log 문자열에 계좌·주민·카드번호 패턴 변수 삽입 감지 → 마스킹 유틸 사용 안내 |
| `prod-db-guard` | 명령 실행 전 | `psql`/`kubectl exec` 로 운영 DB 직접 접속 시도 → 차단, `recon_compare` 등 MCP 도구로 유도 |
| `migration-guard` | Flyway 파일 생성 시 | 기존 버전 파일 수정 금지, 파괴적 DDL(DROP/타입 축소) 경고, `V{timestamp}__` 명명 강제 |
| `arch-guard` | 커밋 전 | ArchUnit 헥사고날 검증 태스크 실행 — domain→adapter 역방향 의존 차단 |

훅은 정규식 1차 + (매치 시) LLM 판정 2차의 2단계로 오탐을 줄인다.

---

## 6. 커맨드 (사용자 진입점)

| 커맨드 | 동작 (Skill + Tool 조합) |
|---|---|
| `/recon-check [date]` | `recon_compare` + `projection_status` + `outbox_lag` 호출 → 불일치를 `recon-playbook` 원인 트리로 분류해 진단 보고 |
| `/fee-audit <PR|파일>` | 수수료·홀드백 코드 diff 를 `money-safety`·`settlement-domain-rules` 기준으로 감사, `settlement_simulate` 로 케이스 검증 |
| `/ledger-verify [기간]` | `ledger_trial_balance` → 불일치 시 원장 불변식 기준 원인 후보 제시 |
| `/settlement-explain <settlementId>` | 정산 1건의 계산 근거(수수료율 스냅샷·홀드백·주기)를 사람이 읽을 수 있게 풀이 — CS/셀러 문의 대응용 |
| `/oncall` | 러너북 skill 로드 → 메트릭 도구 순회 → "지금 어디가 아픈지" 요약 + 다음 조치 제안 |
| `/event-compat <topic>` | `event_schema_diff` 로 배포 전 스키마 하위호환 판정 |
| `/compliance-scan` | 현재 브랜치 diff 를 PII·이력훼손·감사로그 누락 관점으로 스크리닝 |

---

## 7. 플러그인 디렉터리 구조

```
settlement-copilot/
├── AGENTS.md                       # 상시 코어 규칙 (§3.2) — Codex 가 자동 로드
├── plugin.json                     # (Claude Code 호환용 매니페스트)
├── skills/
│   ├── settlement-domain-rules/SKILL.md
│   ├── money-safety/SKILL.md
│   ├── idempotency-and-events/SKILL.md
│   ├── ledger-invariants/SKILL.md
│   ├── recon-playbook/SKILL.md
│   ├── incident-runbooks/SKILL.md
│   └── compliance-review/SKILL.md
├── commands/                       # /recon-check, /fee-audit, ...
│   └── *.md                        # 커맨드 프롬프트 (skill·tool 오케스트레이션 지시)
├── hooks/
│   ├── hooks.json                  # 훅 등록 (pre-write / pre-exec / pre-commit)
│   └── guards/*.py                 # money-type, pii, prod-db, migration 가드
└── mcp/
    ├── server/                     # settlement-copilot-mcp (Spring Boot, 사내 배포)
    └── mcp.json                    # 에이전트 → 서버 연결 설정
```

- Codex CLI 와 Claude Code 는 **skills(SKILL.md)·MCP·훅 개념이 상호 대응**되므로,
  본체(skills/mcp/guards)는 공용으로 두고 매니페스트만 플랫폼별로 분리한다.
- MCP 서버는 플러그인 저장소가 아닌 **플랫폼팀이 배포·운영**하고,
  플러그인에는 접속 설정만 포함한다 (시크릿은 사내 시크릿 매니저 참조).

---

## 8. Lemuel 자산 → 플러그인 매핑 (구현 근거)

| 플러그인 요소 | Lemuel 의 실제 구현 |
|---|---|
| `recon_compare` | `settlement.recon.OrderReconClient` ↔ order `/internal/recon` (X-Internal-Api-Key) |
| `ledger_trial_balance` | `ledger.adapter.in.web.LedgerController`, ADR 0007 원장 불변식 |
| `projection_status` | `adapter.out.readmodel.SettlementProjectionGauges` |
| `dlt_inspect` | ADR 0017 Kafka DLT & replay |
| `settlement_simulate` | `SellerTier` 수수료·`HoldbackPolicy.forTier`·`defaultCycle` 도메인 로직 |
| `pg_recon_status` | `pgreconciliation.adapter.in.web.PgReconciliationController` |
| skill: 상태머신 | `OrderStatus.canTransitionTo()` / Settlement·Payout·Chargeback·Ledger 상태 enum |
| skill: 멱등 | outbox `event_id UNIQUE` + `processed_events` PK + `settlements.payment_id UNIQUE` |
| hook: arch-guard | 기존 ArchUnit 헥사고날 검증 테스트 |
| 감사로그 | `shared-common.common.audit` (PII 마스킹 포함) |

→ 플러그인의 모든 기능이 **이미 검증된 운영 코드 위에 read-only 로 얹히는 구조**라
   신규 위험 표면이 거의 없다. 이것이 금융사 도입 시 가장 큰 셀링 포인트.

---

## 9. 단계별 로드맵

| 단계 | 범위 | 완료 기준 |
|---|---|---|
| **MVP (2주)** | AGENTS.md + skills 3종(domain-rules, money-safety, idempotency) + `money-type-guard` 훅 | 정산 PR 10건에 대해 가드 오탐률 < 10% |
| **Phase 2 (4주)** | MCP 서버(recon_compare, ledger_trial_balance, settlement_simulate) + `/recon-check`, `/fee-audit` | 대사 불일치 1건을 에이전트가 원인 분류까지 수행 |
| **Phase 3 (4주)** | 온콜 도구(projection/outbox/dlt) + `/oncall` + compliance-scan | 러너북 기반 장애 진단 리허설 통과 |
| **Phase 4** | 스키마 diff, 감사로그 대시보드, 멀티 테넌트(부서별 스코프) | 사내 3개 팀 온보딩 |

## 10. 성공 지표

- 정산 관련 PR 의 **금액 타입/멱등성 리뷰 지적 건수** (도입 전 대비 감소율)
- 대사 불일치 **원인 분류까지 걸린 시간** (MTTD)
- 온콜 러너북 조회 → 조치까지의 시간
- 가드레일 오탐률 (개발자 마찰 지표 — 10% 이하 유지)

## 11. 리스크와 완화

| 리스크 | 완화 |
|---|---|
| 에이전트가 도구 출력을 잘못 해석해 오진 | 도구 응답에 기계 판정(`match: false, reason: ...`)을 포함 — 해석 여지 축소 |
| skill 문서와 실제 코드의 드리프트 | skill 을 ADR/러너북에서 CI 로 자동 재생성(요약 파이프라인), 분기마다 검증 |
| PII 유출 | 마스킹을 에이전트가 아닌 **MCP 서버 측**에서 강제 (§4.2) |
| 훅 과차단으로 개발자 이탈 | 2단계 판정 + 훅별 우회 사유 기록(감사) 허용 |
