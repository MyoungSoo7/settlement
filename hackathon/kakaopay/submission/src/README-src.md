# Source Notes — kakaopay-invest-companion

플러그인 런타임 구성:

- `.codex-plugin/plugin.json` — manifest (`skills: ./skills/`, `mcpServers: ./.mcp.json`).
- `.mcp.json` — **Codex 실측 규격** 준수: `mcpServers` 래퍼 + `"cwd": "."`(플러그인 루트로
  해석됨) + 상대경로 args. `${CLAUDE_PLUGIN_ROOT}`/`${VAR}` 치환과 셸 env 상속은 codex-cli
  0.142.5 에서 동작하지 않음을 실측 확인 — 키는 `~/.codex/.env` 파일 폴백으로 전달
  (상세: `../../codex.md` §4).
- `skills/` — 스킬 8종. 응대 계열(anxiety-triage → buy/sell-companion → next-step-guide),
  탐색(stock-explorer), 주기 선별(periodic-picks — 백테스트 통계·매매계획 도구 결합),
  전달 형식(trust-explainer), 복기(trade-retrospective).
- `dart/`, `ecos/`, `naver/`, `common/`, `mcp/` — pwc 제출물(trusted-ceo-agent)의 검증된
  모듈 재사용. 서버명은 `invest-companion-{dart,ecos,news}` 로 리네이밍, 도구 description 은
  투자자 맥락으로 조정 (ECOS: 시장 요인 vs 종목 요인 분리 / 뉴스: 악재 실체 확인,
  기본 악재 키워드를 투자자용 — 유상증자·횡령·배임·거래정지·상장폐지·실적·소송 — 으로 교체).
- `data/sample/` — 합성 매매내역·보유현황. 행동 패턴 정답지는 상위 `README.md` 참조.
- `test/` — MCP 스모크(dart-smoke, ecos-smoke) + 공용 유틸 테스트. 실행:
  `node src/test/ecos-smoke.mjs` (키 없으면 프로토콜 검증만, 키 있으면 라이브 포함).
- `bin/run-sample.ps1` — 데이터 무결성 확인 + 데모 프롬프트 출력 (UTF-8 BOM).

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
```
