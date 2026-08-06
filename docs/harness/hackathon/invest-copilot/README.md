# Invest Copilot

초보 투자자용 **매수·매도 기준** AI 에이전트 플러그인 — 재무제표·경제지표·기업뉴스평판
3개 마이크로서비스(financial-statements/economics/company)를 데이터 소스로,
증권사 관점의 체크리스트·리스크 관리 원칙·표현 컴플라이언스를
**OpenAI Codex CLI** 와 **Claude Code** 에 주입한다. (settlement-copilot 과 동일한 3-Layer 구조)

```
invest-copilot/
├── AGENTS.md               # ① 상시 코어 규칙 (컴플라이언스·데이터 근거·초보자 보호)
├── skills/                 # ① 상황별 도메인 지식 6종
│   ├── buy-sell-criteria/  #    BUY-8 매수 / SELL-7 매도 체크리스트 + 판정 규칙
│   ├── fundamentals-analysis/  # 재무제표 3축(성장·수익·안정) 해석
│   ├── macro-signals/      #    4대 경제지표 → 시장 온도(🟢/🟡/🔴)
│   ├── sentiment-signals/  #    뉴스·평판 스코어 해석 (악재 분류)
│   ├── risk-management/    #    분할매수·손절·포지션 사이징 (이익을 내는 구조)
│   └── compliance-language/#    금지 표현·필수 고지·단정→조건부 변환
├── commands/               # 사용자 진입점 5종
├── hooks/                  # ③ 가드레일 (Claude Code 훅 + git pre-commit 폴백)
│   └── guards/rules.mjs    #    보장/단정 표현 BLOCK · 고지문 WARN · 조회 DB 쓰기 BLOCK
├── mcp/server/index.mjs    # ② 읽기 전용 MCP 서버 (zero-dependency, Node 18+)
├── codex/readonly-db.rules # ③ Codex execpolicy — DB 클라이언트를 승인 프롬프트로 승격
├── scripts/doctor.mjs      # 설치 드리프트·서비스 헬스 진단 (/invest-doctor)
├── .claude-plugin/         # Claude Code 플러그인 매니페스트
├── .mcp.json               # Claude Code MCP 연결
├── install-codex.sh        # Codex CLI 설치/동기화 스크립트 (멱등, --sync)
└── test/smoke.mjs          # 스모크 테스트 (네트워크 불필요)
```

## 철학

- **"사라/팔아라"가 아니라 "기준 충족 여부"** — 판단 근거는 데이터가, 결정은 투자자가.
- **주가 데이터 없음을 인정** — PER/PBR/목표주가 도구는 만들지 않았다. 손절선·익절선 등
  가격 기준은 "HTS 에서 확인할 항목"으로 구분해 제시한다.
- **이익은 종목 선정이 아니라 손실 관리에서** — 모든 추천에 분할매수·손절 규칙 동반.

## MCP 도구 (전부 읽기 전용, GET 만 라우팅)

| 도구 | 백엔드 | 용도 |
|---|---|---|
| `company_search` | financial `/api/financial/companies` | 기업 검색 → stockCode 확정 (모든 도구의 진입점) |
| `fin_statements` | financial `.../statements` | 연간 요약 재무제표 원본 (원 단위, CFS/OFS) |
| `fin_metrics` | financial (+로컬 계산) | 연도별 지표 — 매출 YoY·ROE 는 로컬 계산 보강 |
| `econ_latest` | economics `/api/economics/indicators` | 4대 지표 최신값+전기 대비 변동 |
| `econ_series` | economics `.../{code}/series` | 시계열 (BASE_RATE/TREASURY_3Y/USD_KRW/CPI) |
| `news_recent` | company `.../articles` | 기업 뉴스 (제목·요약·링크 — 본문 미저장) |
| `reputation_score` | company `.../reputation(+history)` | 평판 score/grade + 부정 카테고리 + 추이 |
| `invest_signal` | 3개 서비스 종합 (로컬 판정) | BUY-8 자동 판정 — 항목별 pass/fail/unknown + 하드 필터 + 종합 |
| `guard_check` | 로컬 (rules.mjs 재사용) | 표현 컴플라이언스 사전 검사 (훅 없는 환경용) |

환경변수: `FINANCIAL_BASE_URL`(기본 :8086), `ECONOMICS_BASE_URL`(기본 :8087),
`COMPANY_BASE_URL`(기본 :8090). 전부 무인증 공개 GET — 토큰 불필요.

## 커맨드

| 커맨드 | 용도 |
|---|---|
| `/market-brief` | 4대 지표 순회 → 시장 온도(🟢/🟡/🔴) + 초보자 행동 기준 |
| `/stock-check <기업>` | 종목 종합 진단 — BUY-8 체크리스트 평가 |
| `/buy-checklist <기업> [금액]` | 매수 전 최종 점검 + 분할 매수 계획(3-3-4) |
| `/sell-checklist <기업> [보유정보]` | 매도 신호(S1~S4 자동) + 가격 기준(S5~S7 질문) |
| `/invest-doctor` | 설치 드리프트·구버전 MCP·서비스 헬스 진단 |

## 설치 — Codex CLI

```bash
bash invest-copilot/install-codex.sh   # 멱등 — settlement-copilot 과 공존 (마커/훅 라인 분리)
```

AGENTS.md 마커 병합 → 커맨드(경로 절대화+저장소 가드) → skills → config.toml 마커 블록
(URL 보존) → git hooks(pre-commit 가드 + post-merge/checkout 자동 재동기화) →
설치 매니페스트 → execpolicy 검증. 자세한 동작은 settlement-copilot README 와 동일 패턴.

## 설치 — Claude Code

```bash
claude plugin install ./invest-copilot   # 또는 --plugin-dir 로 로드
```

## 검증 · 진단

```bash
node invest-copilot/test/smoke.mjs      # MCP 왕복 + 가드 규칙 (네트워크 불필요)
node invest-copilot/scripts/doctor.mjs  # 설치 상태 + 데이터 서비스 헬스
```

## 컴플라이언스 원칙

- 보장·단정 표현(예: "수익 보장" — 금지 표현)은 가드가 Write/Edit 시점에 BLOCK 한다.
- 매수/매도 판단이 담긴 문서에 필수 고지문이 없으면 WARN.
- 본 플러그인의 모든 출력은 교육 목적 정보 제공이며 투자자문·투자권유가 아니다.
  투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있다.

## 로드맵

- [x] MVP: AGENTS.md + skills 6종 + 컴플라이언스 가드 + MCP 9도구 + invest_signal 종합 판정
- [ ] 스크리닝: 전 종목 순회 BUY-8 상위 N (financial 목록 API 페이지네이션 순회 — 비용 검토)
- [ ] 업종(market/sector) 보정: 금융업 부채비율 예외 등을 invest_signal 에 반영
- [ ] 평판 추이 이벤트: grade 변동 감지 → 보유 종목 S3 알림 (company 이벤트 연동 검토)
