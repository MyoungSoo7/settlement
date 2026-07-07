# Source Notes — trusted-ceo-agent

플러그인 런타임 구성:

- `.codex-plugin/plugin.json` — manifest. `skills` 는 공식 문서 형식(`"./skills/"` 디렉토리 참조)을 따른다.
- `skills/` — 스킬 5개. 진입점은 `ceo-risk-recon` (오케스트레이터), 나머지 4개는 도메인/출력 스킬.
- `data/sample/` — 합성 데모 데이터. 이상 신호 정답지는 상위 `README.md` 참조.
- `bin/run-sample.ps1` — 데이터 무결성 확인 + 데모 프롬프트 출력.
- `.mcp.json` — DART MCP 서버(`trusted-ceo-agent-dart`) 등록. manifest 의
  `"mcpServers": "./.mcp.json"` 으로 연결됨.
- `dart/` — DART OpenAPI 클라이언트 (zero-dependency). `client.mjs`(API 래퍼,
  DART_API_KEY 는 env 우선 + 상위 `.env` 폴백) + `corp-codes.mjs`(corpCode.zip
  다운로드→자체 zip 해제→`data/cache/corp-codes.json` 7일 캐시, 이름/종목코드 검색).
- `mcp/dart-server.mjs` — 읽기 전용 DART MCP 서버, 도구 6종:
  `dart_corp_search`(진입점 — corp_code 확정) · `dart_company`(기업개황) ·
  `dart_disclosures`(공시 목록, 정정공시 반복 등 행간 신호) ·
  `dart_financial_summary`(주요계정, 당기/전기/전전기) ·
  `dart_financial_full`(전체 재무제표, CFS/OFS) · `dart_status`(키/캐시 점검)
- `bin/dart-cli.mjs` — 수동 점검용: `node src/bin/dart-cli.mjs search 삼성전자`
- `test/dart-smoke.mjs` — MCP 왕복 + (키 있으면) 라이브 검증
- `ecos/client.mjs` — 한국은행 ECOS OpenAPI 클라이언트 (zero-dependency,
  ECOS_API_KEY 는 env 우선 + 상위 `.env` 폴백). StatisticSearch/KeyStatisticList 래퍼 +
  검증된 지표 카탈로그 4종 (이 저장소 economics-service V1 시드와 동일 좌표):
  BASE_RATE(722Y001) · TREASURY_3Y(817Y002) · USD_KRW(731Y001) · CPI(901Y009)
- `mcp/ecos-server.mjs` — 읽기 전용 ECOS MCP 서버, 도구 4종:
  `ecos_indicator`(핵심 4지표 시계열+최신값+변화량) · `ecos_series`(임의 통계 원시 조회) ·
  `ecos_key_stats`(100대 통계지표 스냅숏 — 거시 브리핑용) · `ecos_status`(키/카탈로그 점검)
- `test/ecos-smoke.mjs` — MCP 왕복 + (키 있으면) 라이브 검증

DART 키 발급: https://opendart.fss.or.kr (무료, 일 20,000건). `DART_API_KEY` env 로 주입.
ECOS 키 발급: https://ecos.bok.or.kr → Open API (무료). `ECOS_API_KEY` env 로 주입.
샘플 CSV(`data/sample/`)가 "내부 데이터" 시나리오라면, DART 도구는 "외부 공시" 축,
ECOS 도구는 "거시 환경" 축이다 — 내부 장부 ↔ 공시 재무제표 대사(cross-check)에
금리(이자 부담)·환율(수입 원가)·CPI(원가 전가 여력) 컨텍스트를 결부지어
Trusted CEO Agent 의 실데이터 데모 경로를 완성한다.

## 스킬 호출 흐름

```
사용자: "놓친 리스크 찾아줘"
  └─ ceo-risk-recon (인벤토리 → 디스패치 → 교차검증 → 중요도 → 브리핑)
       ├─ accounting-anomaly     (시산표/분개 → break + 원인 가설)
       ├─ cashflow-bottleneck    (aging/운전자본 → 병목 + 현금 영향)
       ├─ cost-allocation-audit  (배부표 → 민감도 재계산 + 뒤집힘 탐지)
       └─ ceo-briefing           (서명용 보고 형식으로 최종 출력)
```
