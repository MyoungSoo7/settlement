# Settlement Copilot

정산 도메인 특화 AI 에이전트 플러그인 — Lemuel 정산 플랫폼의 도메인 규칙·검증 도구·가드레일을
**OpenAI Codex CLI** 와 **Claude Code** 에 주입한다.
설계 문서: [`docs/design/settlement-codex-plugin.md`](../docs/design/settlement-codex-plugin.md)

```
settlement-copilot/
├── AGENTS.md               # ① 상시 코어 규칙 (Codex 가 자동 로드)
├── skills/                 # ① 상황별 도메인 지식 7종 (SKILL.md)
├── commands/               # 사용자 진입점 6종 (/recon-check, /fee-audit, ...)
├── hooks/                  # ③ 가드레일 (Claude Code 훅 + git pre-commit 폴백)
│   └── guards/rules.mjs    #    money/immutable-history/pii/prod-db/migration 규칙
├── mcp/server/index.mjs    # ② 읽기 전용 MCP 서버 (zero-dependency, Node 18+)
├── .claude-plugin/         # Claude Code 플러그인 매니페스트
├── .mcp.json               # Claude Code MCP 연결
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

환경변수: `SETTLEMENT_BASE_URL`(기본 :8082), `ORDER_BASE_URL`(기본 :8088),
`COPILOT_ADMIN_TOKEN`(admin API JWT), `INTERNAL_API_KEY`(order 내부 API 키).
응답의 계좌/주민/카드 계열 필드는 **서버 측에서 마스킹**되어 에이전트 컨텍스트에 원문이 남지 않는다.

## 설치 — Codex CLI

```bash
# 1) 코어 규칙: 프로젝트 루트에서 작업하면 AGENTS.md 를 Codex 가 자동 인식하도록 병합/링크
#    (모노레포 루트에 이미 AGENTS.md 가 있으면 내용을 append)
cat settlement-copilot/AGENTS.md >> AGENTS.md

# 2) 커맨드 → 커스텀 프롬프트로 복사 (/recon-check 등으로 호출)
mkdir -p ~/.codex/prompts && cp settlement-copilot/commands/*.md ~/.codex/prompts/

# 3) MCP 서버 등록 — ~/.codex/config.toml
[mcp_servers.settlement-copilot]
command = "node"
args = ["<repo>/settlement-copilot/mcp/server/index.mjs"]
env = { SETTLEMENT_BASE_URL = "http://localhost:8082", ORDER_BASE_URL = "http://localhost:8088" }

# 4) 가드레일 폴백 — Codex 는 훅이 없으므로 git pre-commit 으로 강제
echo 'node settlement-copilot/hooks/guards/pre-commit.mjs || exit 1' >> .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

skills 는 Codex 버전이 skills 를 지원하면 `~/.codex/skills/` 로 복사하고,
미지원 버전이면 커맨드 프롬프트가 skill 파일 경로를 직접 읽도록 되어 있어 그대로 동작한다.

## 설치 — Claude Code

```bash
# marketplace 없이 로컬 플러그인으로
claude plugin install ./settlement-copilot   # 또는 --plugin-dir 로 로드
```

플러그인 매니페스트(`.claude-plugin/plugin.json`) 기준으로 commands/skills/hooks/.mcp.json 이
자동 등록된다. 훅은 `${CLAUDE_PLUGIN_ROOT}` 기준 경로라 설치 위치와 무관하게 동작한다.

## 검증

```bash
node settlement-copilot/test/smoke.mjs
# [1] MCP 서버 왕복 (initialize/tools list/simulate 기대값)
# [2] 가드 규칙 (money/history/pii/prod-db 차단·통과 케이스)
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
- [ ] Phase 3(잔여): DLT 인스펙션 도구 (`dlt_inspect`) — DLT 조회 API 신설 필요
- [ ] Phase 4: 이벤트 스키마 diff (`event_schema_diff`) — ADR 0022 레지스트리 연동
