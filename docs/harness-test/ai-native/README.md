# chapter-06 — Zeude 세팅 가이드 (팀 AI 인프라 케이스 스터디)

6장 실습(2006-1-2 팀 AI 인프라 요구사항, 2006-1-3 플레이그라운드 로드맵)의 실물 케이스인
**Zeude**(https://github.com/zep-us/zeude)를 직접 받아서 띄우는 가이드다.

Zeude는 세 층으로 구성된다. ① SENSING — shim이 OTEL 환경변수를 주입해 claude/codex 사용
traces를 ClickHouse로 모은다. ② DELIVERY — 대시보드에 등록한 skills/hooks/MCP를 shim이 매
실행마다 `~/.claude/`로 자동 동기화한다. ③ GUIDANCE — UserPromptSubmit hook이 2-tier 키워드
매칭으로 스킬을 실시간 추천한다. 운영자(본인)가 한 번 세팅하면, 팀원은 설치 한 줄로 합류한다.

스택: Go shim + Next.js 대시보드 + **Supabase**(설정·사용자) + **ClickHouse**(분석) + OTEL.

---

## 0. 사전 준비

| 항목 | 용도 |
|---|---|
| Node.js 20+ / pnpm | 대시보드 (Next.js) |
| Docker (Desktop) | 로컬 ClickHouse + OTEL collector |
| Supabase 계정 + [Supabase CLI](https://supabase.com/docs/guides/cli) | 설정 DB + 마이그레이션 적용 |
| Claude Code CLI | shim이 감싸는 대상 |
| macOS(Intel/Apple Silicon) 또는 Linux(x86_64/arm64) | shim 바이너리 지원 플랫폼 |

## 1. Clone

```bash
git clone https://github.com/zep-us/zeude.git
cd zeude
```

레포 구조가 한 단계 중첩돼 있다 — Go shim·대시보드 코드는 루트가 아니라 `zeude/` 하위에 있다.

```
zeude/                      # ← git clone 루트
├── .env.example            # 환경변수 템플릿 (루트에 있음)
└── zeude/
    ├── cmd/                # Go shim (claude / codex / doctor)
    ├── dashboard/          # Next.js 대시보드
    │   ├── supabase/migrations/    # Supabase 마이그레이션 (25개)
    │   ├── clickhouse/init.sql     # ClickHouse 최종 DDL (migrations 001~015 통합)
    │   ├── clickhouse/migrations/  # ClickHouse 점진 마이그레이션 (001~015)
    │   └── docker-compose.dev.yaml # 로컬 ClickHouse + collector
    ├── deployments/        # 운영용 OTEL collector compose
    └── releases/install.sh # 팀원 설치 스크립트
```

## 2. 환경변수

```bash
cp .env.example .env
```

`.env`에 채울 값:

| 변수 | 값 |
|---|---|
| `SUPABASE_URL` | Supabase 프로젝트 URL (`https://<ref>.supabase.co`) |
| `SUPABASE_ANON_KEY` / `SUPABASE_SERVICE_ROLE_KEY` | Supabase 대시보드 → Settings → API |
| `CLICKHOUSE_URL` | ClickHouse HTTP 엔드포인트 (로컬이면 `http://localhost:8123`) |
| `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` | ClickHouse 계정 (로컬 dev는 `default` / 빈 값) |
| `CLICKHOUSE_DATABASE` | `zeude` |
| `CLICKHOUSE_ENDPOINT` / `CLICKHOUSE_USERNAME` | OTEL collector가 쓰는 값 (3-2 참고) |
| `NEXT_PUBLIC_APP_URL` | 대시보드 URL (로컬이면 `http://localhost:3000`) |
| `OPENROUTER_API_KEY` | 선택. AI 기능용 |

실제 자격증명이 든 `.env`는 절대 커밋하지 않는다.

## 3. ClickHouse 세팅 (SENSING 저장소)

### 3-1. 로컬 개발 — docker-compose.dev.yaml

```bash
cd zeude/dashboard
docker compose -f docker-compose.dev.yaml up -d
```

ClickHouse 24.10(HTTP 8123, Native 9000)과 OTEL collector(gRPC 4317, HTTP 4318)가 함께 뜬다.

### 3-2. 스키마 적용

분석 스키마의 정본은 `clickhouse/init.sql`이다 — migrations 001~015를 통합한 최종 DDL로,
`claude_code_logs`(OTEL 표준 스키마)·`ai_prompts`·`pricing_model` 테이블과
`token_usage_hourly`·`efficiency_metrics_daily`·`retry_analysis`·`frustration_analysis` 등
분석 뷰까지 한 번에 만든다.

```bash
# zeude 데이터베이스 생성 (init.sql에는 CREATE DATABASE가 없다)
curl -s 'http://localhost:8123/' --data 'CREATE DATABASE IF NOT EXISTS zeude'

# 최종 DDL 적용
docker compose -f docker-compose.dev.yaml exec -T clickhouse \
  clickhouse-client --database zeude --multiquery < clickhouse/init.sql
```

**이미 운영 중인 ClickHouse에 점진 적용**하는 경우에는 init.sql 대신
`clickhouse/migrations/001_token_usage_hourly.sql` 부터 `015_...` 까지 번호 순서대로 실행한다.
fresh 설치면 init.sql 한 번이면 되고, migrations를 또 돌릴 필요 없다.

### 3-3. 운영용 OTEL collector

운영 환경에서는 `deployments/docker-compose.collector.yaml`로 collector만 별도로 띄운다.

```bash
cd zeude/deployments
CLICKHOUSE_PASSWORD=<실제 비밀번호> docker compose -f docker-compose.collector.yaml up -d
```

적용 전에 두 군데를 본인 환경에 맞게 고친다:

- `otel-collector-config.yaml`의 ClickHouse exporter — `endpoint`(기본
  `https://localhost:8443`), `database: zeude`, `username: zep`이 하드코딩돼 있다. 본인
  ClickHouse 주소·계정으로 수정한다. 비밀번호는 `${CLICKHOUSE_PASSWORD}` 환경변수로 주입된다.
- `docker-compose.collector.yaml`의 `environment`에 예시 비밀번호가 그대로 적혀 있다 —
  반드시 본인 값으로 바꾸고, compose 파일에 평문 비밀번호를 남기지 않는다.

## 4. Supabase 세팅 (DELIVERY/GUIDANCE 저장소)

skills·hooks·MCP 설정, 사용자·팀·cohort, skill-rules(2-tier 키워드)가 모두 Supabase에 산다.

### 4-1. 프로젝트 생성

[supabase.com](https://supabase.com)에서 새 프로젝트를 만들고, Settings → API의 URL과
anon/service_role 키를 `.env`에 채운다. 프로젝트 ref(`https://<ref>.supabase.co`의 `<ref>`)를
적어 둔다.

### 4-2. 마이그레이션 적용 — Supabase CLI (권장)

마이그레이션은 `zeude/dashboard/supabase/migrations/`에 25개가 타임스탬프 순으로 있다.
초기 스키마(`20251219000001_initial_schema.sql`)부터 skills/hooks 동기화, 2-tier skill hint,
cohort(팀 리더보드), soft delete까지 전부 여기 들어 있다.

레포에는 `supabase/config.toml`이 없으므로 CLI 연결을 한 번 만들어 준다:

```bash
cd zeude/dashboard
supabase init          # supabase/config.toml 생성 — 기존 migrations/는 그대로 둔다
supabase login
supabase link --project-ref <ref>
supabase db push       # migrations/ 를 타임스탬프 순서로 원격 DB에 적용
```

`supabase db push`가 적용 대상 목록을 보여주고 확인을 받는다. 25개 전부 적용되는지 확인한다.

### 4-3. 대안 — SQL Editor 수동 적용

CLI를 쓰기 어려우면 Supabase 대시보드 → SQL Editor에서 `migrations/` 파일을
**파일명 타임스탬프 오름차순으로** 하나씩 실행한다. 순서가 어긋나면 뒤 마이그레이션이
앞 스키마를 전제하므로 실패한다.

## 5. 대시보드 서버

```bash
cd zeude/dashboard
pnpm install
pnpm dev               # http://localhost:3000
```

`.env`는 레포 루트 기준이므로 dashboard에서 읽히는지 확인하고, 안 읽히면
`zeude/dashboard/.env.local`로 복사한다. 운영 배포는 `pnpm build && pnpm start`
(또는 `zeude/dashboard/Dockerfile` / `docker-compose.yaml`).

대시보드가 뜨면:

1. 관리자 계정으로 로그인 → 팀·사용자 구성
2. Skills/Hooks/MCP 등록 — 여기 등록한 것이 팀원 로컬로 자동 동기화된다
3. agent key 발급 (`zd_` 접두) — 팀원 설치에 쓴다

## 6. 팀원 설치 — shim 한 줄

운영자 세팅이 끝나면 팀원은 이 한 줄이면 된다:

```bash
curl -fsSL https://<dashboard-url>/releases/install.sh | ZEUDE_AGENT_KEY=zd_xxx bash
```

설치 스크립트가 하는 일:

- `~/.zeude/bin`에 `claude` shim과 `zeude`(doctor) 바이너리를 내려받고 PATH에 등록
- `~/.zeude/credentials`에 agent_key 저장, `~/.zeude/config`에 dashboard URL 저장
- claude shim이 codex shim을 companion으로 자동 설치 — 같은 agent_key가 두 shim을
  한 사용자로 묶어 ClickHouse에서 통합 집계된다

이후 팀원은 평소대로 `claude`를 실행하면 된다. shim이 명령을 가로채 FastSync(<100ms)로
최신 skills/hooks/MCP를 동기화하고 OTEL 환경변수를 주입한 뒤 진짜 CLI를 실행한다.

동기화 범위는 런타임마다 다르다 — **Claude는 skills+hooks+MCP+agents 전부,
Codex는 skills만**(`~/.claude/skills` + `~/.codex/skills`) 동기화된다.

> install.sh의 기본 다운로드 주소는 placeholder(`https://your-dashboard-url`)다. 본인
> 대시보드 도메인으로 서빙하거나, `ZEUDE_DOWNLOAD_BASE`/`ZEUDE_DASHBOARD_URL` 환경변수로
> 지정한다.

## 7. 동작 확인

1. **SENSING** — 팀원 머신에서 `claude`로 아무 작업이나 한 뒤, ClickHouse에서 확인:
   ```sql
   SELECT ServiceName, count() FROM zeude.claude_code_logs GROUP BY ServiceName
   -- claude / codex 두 ServiceName이 쌓이면 정상
   ```
2. **DELIVERY** — 대시보드에 스킬 하나를 등록하고 `claude`를 다시 실행 →
   `~/.claude/skills/`에 내려와 있는지 확인
3. **GUIDANCE** — 등록 스킬의 키워드가 들어간 프롬프트를 입력 → UserPromptSubmit hook이
   스킬을 추천하는지 확인
4. **리더보드(동기 3)** — 대시보드의 leaderboard / skills analytics에서 usageCount·userCount·
   topUsers가 집계되는지 확인. 비개발자·빌더가 만든 스킬이 랭킹에 반영되는 흐름이
   6장 로드맵 실습(2006-1-3)의 3단계 근거다.

## 막히면

- **collector가 ClickHouse에 못 쓴다** — `otel-collector-config.yaml`의 endpoint가 본인
  환경과 맞는지부터. 로컬 dev compose는 평문 8123, 운영 예시는 TLS 8443 기준이라 다르다.
- **`supabase db push`가 중간에 실패** — 이미 일부만 적용된 상태일 수 있다.
  `supabase migration list`로 적용 현황을 보고 이어서 적용한다.
- **shim 설치 후에도 원래 claude가 실행된다** — `which claude`로 `~/.zeude/bin/claude`가
  PATH에서 먼저 잡히는지 확인하고, 셸을 다시 연다. `zeude doctor`가 진단을 도와준다.
