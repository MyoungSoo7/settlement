# Source Notes — kakaopay-invest-companion

플러그인 런타임 구성:

- `.codex-plugin/plugin.json` — manifest (`skills: ./skills/`, `mcpServers: ./.mcp.json`).
- `.mcp.json` — **Codex 실측 규격** 준수: `mcpServers` 래퍼 + `"cwd": "."`(플러그인 루트로
  해석됨) + 상대경로 args. `${CLAUDE_PLUGIN_ROOT}`/`${VAR}` 치환과 셸 env 상속은 codex-cli
  0.142.5 에서 동작하지 않음을 실측 확인 — 키는 `~/.codex/.env` 파일 폴백으로 전달
  (상세: `../../codex.md` §4).
- `skills/` — 스킬 9종. 하네스(invest-cycle — 상태 전이는 `bin/invest-cycle.mjs` CLI 소유),
  응대 계열(anxiety-triage → buy/sell-companion → next-step-guide),
  탐색(stock-explorer), 주기 선별(periodic-picks — 백테스트 통계·매매계획 도구 결합),
  전달 형식(trust-explainer), 복기(trade-retrospective).
  종목당 비중 상한은 **총자산 20%** 로 전 스킬 공통 (periodic-picks·buy-companion·trade-retrospective 동일 수치).
- `dart/`, `ecos/`, `naver/`, `common/`, `mcp/` — pwc 제출물(trusted-ceo-agent)의 검증된
  모듈 재사용. 서버명은 `invest-companion-{dart,ecos,news}` 로 리네이밍, 도구 description 은
  투자자 맥락으로 조정 (ECOS: 시장 요인 vs 종목 요인 분리 / 뉴스: 악재 실체 확인,
  기본 악재 키워드를 투자자용 — 유상증자·횡령·배임·거래정지·상장폐지·실적·소송 — 으로 교체).
- `data/sample/` — 합성 매매내역·보유현황. 행동 패턴 정답지는 상위 `README.md` 참조.
- `test/` — 결정론 스위트 4종 + `test/unit/`(fetch 스텁 기반 — MCP 서버 4종 왕복·오류경로,
  price 재시도/캐시, invest-cycle 게이트를 **키·네트워크 0** 으로 검증) + MCP 스모크 4종(라이브).
  전체 실행: `node src/test/run-all.mjs` (키 없으면 스모크는 프로토콜 검증만).
- `bin/invest-cycle.mjs` — invest-cycle 하네스의 결정론 상태머신 CLI. 단계 전이(`advance`)·
  게이트 판정·주차 카운트(`check`)·대장 기록(`watch`/`plan`/`exclude`/`rule`)을 코드로 강제
  (게이트 실패 exit 1). 스킬은 status 로 읽기만 — JSON 직접 수정 금지.
- `bin/run-sample.ps1` — 데이터 무결성 확인 + 데모 프롬프트 출력 (UTF-8 BOM).
- `bin/sector-matrix.mjs` → `data/stats/sector-matrix.json` — 산업군(유니버스 수동 태깅
  12개 군)×시기(3개년+최근분기) 재무 규칙 5종 충족도 사전계산 (backtest.mjs 패턴).
  MCP `sector_suitability`(dart 서버)가 서빙. 금융업은 부채비율 규칙 N/A.
- `bin/sector-report.mjs` — 매트릭스 + 현재 시점 뉴스 악재 스캔(30일, 소급 불가 명시) →
  `outputs/sector-suitability-<날짜-시간>.docx`. docx 는 `common/docx.mjs` 가
  zero-dependency 로 직접 작성(무압축 OPC zip — corp-codes.mjs 의 zip 해제와 대칭).

## 스킬 호출 흐름

```
불안 호소 ─────────► anxiety-triage ──┬─ 손실공포 → sell-companion
                                      ├─ FOMO    → buy-companion
"뭐부터 하지?" ────► next-step-guide  ├─ 정보부족 → 뉴스/DART/ECOS 사실 확인
"뭘 사야 할지…" ──► stock-explorer ──► (후보 좁히기) → buy-companion
"분기/월/연 3종목+가격" ► periodic-picks (실측승률 → 규칙 스크리닝 → 밴드/손절/익절)
"지금 살까/팔까?" ─► buy/sell-companion┘
"내 매매 봐줘" ────► trade-retrospective (패턴 탐지 → 규칙 제안)
                          │
모든 판정의 출력 ──► trust-explainer (결론→근거→반대근거→한계·고지)

[하네스] "사이클 시작/주간 점검/지금 뭐 해?" ► invest-cycle (관리자)
  bin/invest-cycle.mjs CLI 가 단일 진실(전이·게이트 결정론, 실패 exit 1)
  ─ P1 선별(periodic-picks→watch) → P2 dry run(plan_trade+buy-companion→plan)
  → P3 4주 추적(check, sell-companion/anxiety-triage) → P4 리포트 → P5 회고(trade-retrospective→rule)
  → 포트폴리오 대장(portfolio.json = 에셋 보관함, P1 과 양방향) → 다음 사이클(advance)
```
