# KakaoPay Invest Companion — 해커톤 제출 패키지 (AX 인재전쟁 Round 10)

초보 투자자가 매수·매도 시 겪는 **불안감과 정보 부족**(카카오페이증권 AI 서비스센터가 정의한
문제)을 해결하는 Codex 플러그인. 분석 결과를 던지는 대신,
**감정 인정 → 데이터 사실확인 → 규칙 상기 → 다음 단계 1개** 의 설득 구조로 사용자를
안심시켜 다음 행동으로 이끈다. 종목 추천을 하지 않는다 — "지금 이 결정에서 확인하지
않은 것"을 짚어주는 동행 도구다.

## 스킬 구성 (9종 — 하네스 1 + 서브 8)

| 스킬 | 역할 |
|---|---|
| `invest-cycle` ★하네스 | **관리자 스킬** — "선별→주문계획(dry run)→4주 추적→리포트→회고→대장 갱신" 사이클을 상태 파일(`outputs/cycle/state.json`)로 운영. 직접 분석하지 않고 단계별 서브 스킬에 위임 후 결과를 포트폴리오 대장(에셋 보관함)에 기록 |
| `anxiety-triage` | 불안 발화를 4유형(손실 공포/FOMO/정보 부족/확신 부족)으로 분류하고 4단계 프로토콜로 응대 |
| `stock-explorer` | "뭘 사야 할지 모르겠어요" — 관심사 → DART 재무 스크리닝 → 후보 2~3개 비교표. 종목을 골라주는 게 아니라 **좁히는 절차를 가르친다** |
| `periodic-picks` | "이번 분기(월/연) 뭐 살까 + 얼마에 사고팔까" — 실측 승률(월 51%/분기 54%/연 56%, 10년 백테스트) 기대치 세팅 → 규칙 5종 라이브 스크리닝 상위 3종목 → 종목별 3분할 매입 밴드·손절 -7%·익절 +20% 기준가. **"오를 확률 90%" 는 지어내지 않는다 — 그 요구의 정직한 대체물** |
| `buy-companion` | 매수 직전 5분 체크 — 한 문장 이유 → 사실 확인(DART/ECOS) → 반대 시나리오 → 손절선·비중 규칙 |
| `sell-companion` | "공포 매도인가 원칙 매도인가" 분리 — 원칙 소환 → 시장/종목 요인 분리 → 판정 매트릭스 |
| `trade-retrospective` | 매매 내역에서 반복 행동 패턴 탐지(추격매수·물타기·집중·처분효과·과잉회전) → 규칙 1~2개 제안 |
| `next-step-guide` | 투자 단계(S0~S5) 상태머신 — 상태별 **다음 행동 딱 1개** 제시 (결정 마비 해소) |
| `trust-explainer` | 모든 판정 전달 시 "결론 → 근거(출처·시점) → **반대 근거** → 한계·고지" 4단 구조 강제 |

## 데이터 축 (읽기 전용 MCP 4종)

- **DART** (`invest-companion-dart`): 기업 검색·개황·공시 목록·재무제표 — 불안의 실체(악재)가
  공시로 확인되는지, 펀더멘털 훼손 여부 판단. `DART_API_KEY` env.
  - `sector_suitability`: 산업군(12개 군)×시기(3개년+최근분기) 재무 규칙 5종 충족도 매트릭스
    (사전계산 서빙 — 재계산: `node src/bin/sector-matrix.mjs`). 점수는 예측이 아니라 규칙 충족 개수.
- **ECOS** (`invest-companion-ecos`): 기준금리·국고채3년·환율·CPI — 하락이 시장 전체
  요인인지 종목 고유 요인인지 분리. `ECOS_API_KEY` env.
- **뉴스** (`invest-companion-news`): 네이버 뉴스 검색 — "이 뉴스가 악재인가요?"(불안 4유형 중
  정보 부족)에 실보도로 답한다. 악재 키워드(유상증자·횡령·거래정지 등) 스캔 포함,
  **뉴스가 없으면 없다고 말할 근거**가 된다. 기사 본문 미수집(제목·요약·링크만).
  `NAVER_CLIENT_ID`/`NAVER_CLIENT_SECRET` env.
- **시세** (`invest-companion-price`): KOSPI/KOSDAQ 현재가·전일대비·52주 고저·연속 상승/하락
  일수(streak) — "지금 얼마인데?", "사흘 연속 오르는 중 아닌가?"(추격매수 규칙 판정)에 답한다.
  키 불필요. **데모용 공개 어댑터**(Yahoo Finance, 지연 시세 가능)로, 실서비스에서는
  `price/client.mjs` 한 파일만 증권사 사내 시세 API 로 교체하면 도구 계약이 유지된다.
  stockCode 는 DART `dart_corp_search` 와 그대로 조인.
  - `plan_trade`: 예산 → 3분할 매입 밴드 + 손절/익절 기준가·수량 (KRX 호가단위, 예측이 아닌 규칙)
  - `backtest_stats`: 유니버스 66종목·10년 실측 승률·수익 분포 (재계산: `node src/bin/backtest.mjs`)
  - `universe_list`: 스크리닝 후보 풀 (선정 기준·생존 편향 주의 동봉)
- 같은 저장소의 invest-copilot MCP(`invest_signal`, `fin_metrics`, `reputation_score`)가
  있으면 병용하고, 도구가 없는 환경에서는 사용자에게 사실을 질문하는 방식으로 우아하게 강등된다.

## Structure

```text
submission/
|-- .agents/plugins/marketplace.json     # 이 폴더 자체가 codex 마켓플레이스
|-- .env.example                         # 키 원스톱 셋업 (발급처·부재 시 동작 명시)
|-- src/
|   |-- .codex-plugin/plugin.json        # manifest (skills + mcpServers)
|   |-- .mcp.json                        # MCP 4종 등록 (Codex 실측 규격 — cwd:"." + 상대경로)
|   |-- skills/                          # 스킬 9종 (위 표)
|   |-- mcp/{dart,ecos,news,price}-server.mjs  # 읽기 전용 MCP 서버 (zero-dependency stdio)
|   |-- dart/ ecos/ naver/ price/ common/      # API 클라이언트 + 공용 모듈
|   |-- data/sample/                     # 합성 매매내역·보유현황 (정답지 내장)
|   |-- test/                            # 결정론 4종 + unit/(네트워크 0) + 스모크 4종 + 단일 러너 (run-all.mjs)
|   |-- bin/invest-cycle.mjs             # 투자 사이클 상태머신 CLI (전이·게이트 결정론)
|   |-- bin/install-codex.ps1            # 원큐 설치 (등록→설치→승인→키→스모크)
|   |-- bin/run-sample.ps1               # 데모 부트스트랩
|   `-- README-src.md
|-- README.md
`-- logs/          # E2E 검증 증거 (e2e-*.out.md) + Stop 훅 대화 로그
```

## 설치 (Codex) — 원큐, 실검증 완료

```powershell
powershell -ExecutionPolicy Bypass -File src/bin/install-codex.ps1
```

마켓플레이스 등록 → 플러그인 설치 → MCP 승인 config 병합 → `~/.codex/.env` 스캐폴드 →
테스트 러너(run-all) 검증까지 한 번에 (멱등 — 재실행 안전).

<details><summary>수동 설치 (스크립트 없이)</summary>

이 submission 폴더 자체가 마켓플레이스다 (`.agents/plugins/marketplace.json` 내장):

```bash
codex plugin marketplace add <이 submission 폴더 경로 또는 repo>
codex plugin add kakaopay-invest-companion@kakaopay-invest-companion-market
```

이후 2개 파일만 복사:

1. **MCP 도구 승인**: `~/.codex/config.toml` 끝에 MCP 서버 4종(dart/ecos/news/price) 각각
   `enabled = true` + `default_tools_approval_mode = "approve"` 블록 추가 — 정확한 TOML 은
   `src/bin/install-codex.ps1` 의 `$snippet` 히어스트링 참조 (모든 도구가 읽기 전용 조회라
   approve 가 안전. 없으면 비대화 모드에서 MCP 호출이 자동 취소됨 — 실측).
2. **API 키**: `.env.example` 을 `~/.codex/.env` 로 복사해 값 채우기 (플러그인 MCP 서버는
   셸 env 를 상속하지 않으므로 이 위치가 정답 — 실측. 키가 없어도 시세 축과 샘플 데모는 동작).

</details>

> codex-cli 0.142.5 에서 설치 → `installed, enabled` → **Codex 실세션 E2E 에서 MCP 4종
> 12개 도구 실호출 + 스킬 프로토콜 준수 응답**(`logs/e2e-sell-anxiety2.out.md`)까지 검증했다.
> 제거는 `codex plugin marketplace remove kakaopay-invest-companion-market`.

키 설정(선택): `.env.example` 을 `.env` 로 복사해 채우면 끝 — 키별 발급처와 부재 시
동작이 파일 안에 적혀 있다. 키가 없으면 해당 데이터 축만 우아하게 강등되고
(스킬이 사용자에게 사실을 직접 질문), 샘플 데이터 데모는 키 없이 완전 동작한다.

사전 점검(선택): `node src/test/run-all.mjs` — 결정론 스위트 4개 + 단위(unit, 키·네트워크
없이 MCP 4종 왕복·오류경로까지 검증) + MCP 스모크 4종이 ALL GREEN 인지 한 번에 확인
(키 있으면 스모크가 라이브 검증 포함).

## 데모 시나리오

**원샷 리포트** — 한 명령으로 추천 리포트를 `outputs/result-<오늘날짜>.md` 로 저장
(같은 날 재실행 시 `-2`, `-3` 순번 — 이전 리포트를 덮어쓰지 않음):

```bash
node src/bin/recommend.mjs                                    # "주식 투자 추천해줘" 기본 실행
node src/bin/recommend.mjs "이번 분기 300만원, 3종목과 가격"   # 프롬프트 지정 (--out 저장경로)
```

대화 세션에서도 stock-explorer / periodic-picks 스킬이 최종 리포트를
`outputs/result-<날짜>.md` 로 저장하고 경로를 알려준다.

**산업군 리포트 (docx)** — 산업군(12개 군)×시기(3개년+최근분기) 재무 규칙 충족도 표에
현재 시점 뉴스 악재 스캔을 얹어 Word 문서로 저장:

```bash
node src/bin/sector-matrix.mjs      # 재무 매트릭스 사전계산 (DART, 66종목×4시기, 1~2분)
node src/bin/sector-report.mjs      # → outputs/sector-suitability-<날짜-시간>.docx (--no-news 로 뉴스 생략)
```

뉴스 열이 시기별이 아닌 이유: 뉴스 검색은 과거 시점 소급 조회가 불가능하다 — 지어내는
대신 "현재 기준 최근 30일" 한 칸만 제공한다 (재무 축만 시기별). zero-dependency 원칙은
docx 생성에도 유지된다(`src/common/docx.mjs` — 무압축 OPC zip 직접 작성).

**투자 사이클 (하네스)** — 상태머신 CLI(`src/bin/invest-cycle.mjs`) 기반 루틴 운영.
단계 전이·게이트(빈 단계 건너뛰기 차단)·주차 카운트는 CLI 가 결정론으로 강제하고
(게이트 실패 exit 1), 스킬은 그 위에서 대화만 담당한다. 대화 세션에서:

```text
"투자 사이클 시작해줘. 예산 300만원, 4주 주기."   → init (상태 파일 생성, P1 진입)
"지금 뭐 해야 해?"                              → status 읽고 현재 단계 이어가기
"주간 점검 해줘"                                → P3 추적: check (손절선·비중 20%·악재)
```

비대화(`codex exec`)로 돌릴 때는 `-s workspace-write` 필수 — 샌드박스가 read-only 로
뜨면 상태 파일(`outputs/cycle/state.json`)을 못 쓴다 (실측).

```powershell
powershell -ExecutionPolicy Bypass -File src/bin/run-sample.ps1
```

프롬프트: **"src/data/sample 의 매매 내역과 보유 현황을 복기해서, 반복되는 행동 패턴을
찾아 근거와 함께 설명하고 다음 매매 규칙을 제안해줘."**

### 심어둔 행동 패턴 (정답지 — 심사/검증용)

| # | 패턴 | 데이터 근거 | 기대 추론 |
|---|------|------------|----------|
| 1 | **추격매수 → 대형 손절 (버즈테크)** | 3/5·3/6·3/9 사흘 연속 단가 상승 중 3회 매수(52,000→58,000→63,500), 평단 59,111 → 4/14 전량 41,300 매도 | 실현손실 약 **-80만원**(-30.1%), 계좌 최대 단일 손실. "3거래일 연속 상승 중 신규 매수 금지" 규칙 제안 |
| 2 | **물타기 가속 + 집중 (한빛전자)** | 하락마다 4회 매수, 수량이 20→30→50→80 으로 **점증**. 평단 17,617 vs 현재가 13,900 | 평가손 -21.1%(-67만원), 포트폴리오 비중 **75.5%** 집중. "추가 매수는 직전 수량 이하 + 종목 비중 상한" 제안 |
| 3 | **처분효과 (대성식품 vs 우리바이오)** | 대성식품 +6.2% 를 5거래일 만에 익절 / 우리바이오 -30% 를 3개월+ 보유 중 | 이익은 조기 실현, 손실은 방치 — 손익비 악화의 고전 패턴. 손절선 규칙 부재를 지적 |

세 패턴 모두 **개별 거래 한 건만 봐서는 안 보이고**, 시간순 재구성과 두 파일 교차
(holdings 의 한빛전자 평단 17,617 은 trades 4회 매수에서 재계산 가능)에서만 드러나도록 설계했다.
삼성전자(+3%, 단일 소액 매수)는 정상 행동 대조군.

## 컴플라이언스

모든 스킬은 보장/단정 표현("수익 보장" 등) 금지, 지시형("사라/팔아라") 대신 기준 충족형
서술, 레버리지·미수·신용 제안 금지, 응답 말미 필수 고지문을 강제한다. 본 패키지의 출력은
교육 목적 정보 제공이며 투자자문·투자권유가 아니다.
