# Settlement Copilot

정산 도메인 특화 AI 에이전트 플러그인 — Lemuel 정산 플랫폼의 도메인 규칙·검증 도구·가드레일을
**OpenAI Codex CLI** 와 **Claude Code** 에 주입한다.
설계 문서: [`docs/design/settlement-codex-plugin.md`](../../docs/design/settlement-codex-plugin.md)

```
settlement-copilot/
├── AGENTS.md               # ① 상시 코어 규칙 (Codex 가 자동 로드)
├── skills/                 # ① 상황별 도메인 지식 8종 (SKILL.md)
├── commands/               # 사용자 진입점 8종 (/recon-check, /fee-audit, /copilot-doctor, ...)
├── hooks/                  # ③ 가드레일 (Claude Code 훅 + git pre-commit 폴백)
│   └── guards/rules.mjs    #    money/immutable-history/pii/prod-db/migration 규칙
├── mcp/server/index.mjs    # ② 읽기 전용 MCP 서버 (zero-dependency, Node 18+)
├── codex/prod-db.rules     # ③ Codex execpolicy — DB 클라이언트/직접 produce 를 승인 프롬프트로 승격
├── scripts/doctor.mjs      # 설치 드리프트·구버전 MCP 서버 진단 (/copilot-doctor)
├── .claude-plugin/         # Claude Code 플러그인 매니페스트
├── .mcp.json               # Claude Code MCP 연결
├── install-codex.sh        # Codex CLI 설치/동기화 스크립트 (멱등, --sync 지원)
└── test/smoke.mjs          # 스모크 테스트 (네트워크 불필요)
```

## MCP 도구 (전부 읽기 전용)

| 도구 | 백엔드 | 용도 |
|---|---|---|
| `recon_run` | settlement `/admin/reconciliation` | 일일 대사 실행 — matched 여부 |
| `order_recon_totals` | order `/internal/recon/*` | order 원천 합계 (교차 검증 기준값) |
| `ledger_entries` | settlement `/api/ledger/entries` | 기간 원장 + 시산표(차/대 균형) |
| `projection_status` | Prometheus `settlement.projection.*` | 프로젝션 뷰 적재 상태 |
| `outbox_status` | Prometheus `outbox.*` | Outbox pending/failed 적체 |
| `pg_recon_runs` | settlement `/admin/pg-reconciliation/runs` | PG 대사 실행 이력 |
| `settlement_simulate` | 로컬 계산 (SellerTier/HoldbackPolicy 미러) | 수수료·홀드백 dry-run |
| `integrity_check` | settlement `/admin/integrity/*` 순회 | 정합성 종합 (INV-5/6/7/11) — "돈이 새는가" 진입점 |
| `ledger_completeness` | settlement `/admin/integrity/ledger-completeness` | INV-5 원장 완전성 — 시산표가 못 잡는 통짜 누락 감지 |
| `payout_recon` | settlement `/admin/integrity/payout-recon` | INV-6 지급 대사 — 과다/이중 지급 |
| `holdback_status` | settlement `/admin/integrity/holdback-status` | INV-7 해제 기한 경과 홀드백 |
| `stuck_states` | settlement `/admin/integrity/stuck` | INV-11 상태 체류 (SENDING payout = 이중지급 위험) |
| `refund_adjustments` | settlement `/admin/integrity/refund-adjustments` | INV-8 지연 환불 조정 대사 (완료일 축) |
| `event_accounting` | order period-totals + settlement processed-count | INV-10 발행↔소비 이벤트 회계 (gap 판정) |
| `guard_check` | 로컬 (hooks/guards/rules.mjs 재사용) | 가드 규칙 사전 검사 — 실시간 훅이 없는 환경(Codex)에서 금액 파일 쓰기·위험 명령 실행 전 자가 검증 |

환경변수: `SETTLEMENT_BASE_URL`(기본 :8082), `ORDER_BASE_URL`(기본 :8088),
`COPILOT_ADMIN_TOKEN`(admin API JWT), `INTERNAL_API_KEY`(order 내부 API 키).
응답의 계좌/주민/카드 계열 필드는 **서버 측에서 마스킹**되어 에이전트 컨텍스트에 원문이 남지 않는다.

## 설치 — Codex CLI

```bash
bash settlement-copilot/install-codex.sh   # 멱등 — 재실행해도 중복 누적 없음
```

스크립트가 하는 일 (모두 멱등):

1. **코어 규칙** — 루트 `AGENTS.md` 에 마커 블록(`<!-- settlement-copilot:begin/end -->`)으로
   병합. 재실행하면 블록만 교체된다 (append 중복 없음)
2. **커맨드** — `commands/*.md` → `~/.codex/prompts/` 복사.
   복사 시 `settlement-copilot/...` 상대경로를 **저장소 절대경로로 치환**하고
   저장소 가드 문구를 삽입한다 (다른 프로젝트에서 호출돼도 오동작하지 않음)
3. **skills** — `skills/` → `~/.codex/skills/` 복사
4. **MCP 서버** — `~/.codex/config.toml` 의 `[mcp_servers.settlement-copilot]` 블록을
   **마커 방식으로 교체 갱신** (레거시 무마커 블록도 자동 이전, `COPILOT_ADMIN_TOKEN` /
   `INTERNAL_API_KEY` / base URL 커스텀 값은 보존)
5. **git hooks** — `.git/hooks/pre-commit` 가드(최종 방어선) +
   `post-merge`/`post-checkout` 에 `--sync` 자동 재동기화 (pull/브랜치 전환 시 2·3·4 를 자동 갱신)
6. **설치 매니페스트** — `~/.codex/.settlement-copilot-manifest.json` 에 소스 해시 기록
   (doctor 가 드리프트 감지에 사용)
7. **execpolicy** — `codex/prod-db.rules` 검증. Codex 세션에서 DB 클라이언트·직접 produce
   명령을 승인 프롬프트로 승격하는 데 쓸 수 있다:
   `codex execpolicy check --rules settlement-copilot/codex/prod-db.rules <명령>`

skills 를 지원하지 않는 Codex 버전이어도, 각 커맨드 프롬프트에 SKILL.md **절대 경로**가
병기되어 있어 에이전트가 파일을 직접 읽는다. `CODEX_HOME` 환경변수로 `~/.codex` 위치를 바꿀 수 있다.
skill 수정 후 재동기화는 post-merge/post-checkout 훅이 자동으로 하지만, 수동 수정 직후라면
`install-codex.sh --sync` 를 실행한다.

**Codex 의 실시간 가드 한계**: Codex 에는 Claude Code 의 PreToolUse 훅이 없다. 대신
① AGENTS.md 계약에 따라 에이전트가 금액 파일 쓰기 전 MCP `guard_check` 로 자가 검증하고,
② execpolicy 가 위험 명령을 승인 프롬프트로 올리며, ③ git pre-commit 이 최종 방어선이다.

## 설치 — Claude Code

```bash
# marketplace 없이 로컬 플러그인으로
claude plugin install ./settlement-copilot   # 또는 --plugin-dir 로 로드
```

플러그인 매니페스트(`.claude-plugin/plugin.json`) 기준으로 commands/skills/hooks/.mcp.json 이
자동 등록된다. 훅은 `${CLAUDE_PLUGIN_ROOT}` 기준 경로라 설치 위치와 무관하게 동작한다.

## 검증 · 진단

```bash
node settlement-copilot/test/smoke.mjs
# [1] MCP 서버 왕복 (initialize/tools list/guard_check/simulate 기대값)
# [2] 가드 규칙 (money/history/pii/prod-db 차단·통과 케이스)

node settlement-copilot/scripts/doctor.mjs   # 또는 /copilot-doctor
# 설치본 드리프트·config.toml 경로·git hooks·AGENTS.md 블록 진단 +
# 기대 MCP 도구 목록 출력 (세션에 보이는 도구와 비교해 구버전 서버 감지)
```

## 보안 원칙 (금융사 배포 시)

- MCP 서버는 **GET 만 라우팅** — 쓰기 API 는 코드에 존재하지 않는다 (read-only by construction).
- PII 마스킹은 에이전트가 아니라 서버 측에서 수행.
- admin 토큰은 `copilot:read` 성격의 최소 권한 계정으로 발급, 사내 시크릿 매니저로 주입.
- 사내망 배포 전제 — 외부 인터넷 경유 금지. 도구 호출 감사로그는 게이트웨이/서비스 감사 체계 재사용.

## 로드맵 (설계서 §9)

- [x] MVP: AGENTS.md + skills + 가드 + simulate
- [x] Phase 2: recon/ledger MCP 도구 + /recon-check, /fee-audit
- [x] Phase 3(부분): projection/outbox 상태 도구 + /oncall
- [x] Integrity Suite Phase A: `/admin/integrity/*` 4종 + integrity 도구 5종 + /integrity-check
      (INV-5/6/7/11 — `docs/design/settlement-integrity-suite.md`)
- [x] Integrity Suite Phase B: 건수 대사(INV-9, recon_run 건수 축) + 재대사 윈도우(window=N)
      + 지연 환불 조정 대사(INV-8, refund_adjustments) + 이벤트 회계(INV-10, event_accounting)
- [x] 듀얼 플랫폼 사용성 강화: guard_check MCP 자가 검증 + execpolicy rules + config.toml
      마커 갱신(토큰 보존) + post-merge/checkout 자동 재동기화 + /copilot-doctor 드리프트 진단
- [ ] Phase 3(잔여): DLT 인스펙션 도구 (`dlt_inspect`) — DLT 조회 API 신설 필요
- [ ] Phase 4: 이벤트 스키마 diff (`event_schema_diff`) — ADR 0022 레지스트리 연동
