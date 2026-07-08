# Source Notes — kakaopay-invest-companion

플러그인 런타임 구성:

- `.codex-plugin/plugin.json` — manifest (`skills: ./skills/`, `mcpServers: ./.mcp.json`).
- `skills/` — 스킬 6종. 응대 계열(anxiety-triage → buy/sell-companion → next-step-guide)과
  전달 형식(trust-explainer), 복기(trade-retrospective).
- `dart/`, `ecos/`, `common/`, `mcp/` — pwc 제출물(trusted-ceo-agent)의 검증된 모듈 재사용.
  서버명만 `invest-companion-dart` / `invest-companion-ecos` 로 리네이밍, ECOS 도구
  description 은 투자자 맥락(시장 요인 vs 종목 요인 분리)으로 조정.
- `data/sample/` — 합성 매매내역·보유현황. 행동 패턴 정답지는 상위 `README.md` 참조.
- `test/` — MCP 스모크(dart-smoke, ecos-smoke) + 공용 유틸 테스트. 실행:
  `node src/test/ecos-smoke.mjs` (키 없으면 프로토콜 검증만, 키 있으면 라이브 포함).
- `bin/run-sample.ps1` — 데이터 무결성 확인 + 데모 프롬프트 출력 (UTF-8 BOM).

## 스킬 호출 흐름

```
불안 호소 ─────────► anxiety-triage ──┬─ 손실공포 → sell-companion
                                      ├─ FOMO    → buy-companion
"뭐부터 하지?" ────► next-step-guide  ├─ 정보부족 → DART/ECOS 사실 확인
"지금 살까/팔까?" ─► buy/sell-companion┘
"내 매매 봐줘" ────► trade-retrospective (패턴 탐지 → 규칙 제안)
                          │
모든 판정의 출력 ──► trust-explainer (결론→근거→반대근거→한계·고지)
```
