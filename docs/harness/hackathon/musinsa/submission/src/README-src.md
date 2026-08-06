# src — musinsa-fashion-first 플러그인 소스

상위 [`../README.md`](../README.md) 가 전체 서사(가설→해결 수미상관, 비즈니스 임팩트,
설치·데모)를 담당한다. 이 문서는 소스 구조만 요약한다.

```text
src/
|-- .codex-plugin/plugin.json   # manifest — skills 디렉터리 + .mcp.json 참조
|-- .mcp.json                   # MCP 4종 (cwd:"." + 상대경로 — codex 실측 규격)
|-- skills/                     # 스킬 9종 (8 + closet-ops 관리자 하네스)
|-- mcp/
|   |-- json-rpc-stdio.mjs      # 공용 stdio JSON-RPC 서버 (zero-dependency)
|   |-- shop-server.mjs         # 상품·가격 축 (shop_search / price_band / brand_snapshot / shop_status)
|   |-- trend-server.mjs        # 트렌드 축 (buzz_trend / trend_compare / datalab_shopping_trend / trend_status)
|   |-- news-server.mjs         # 평판·기회 축 (fashion_news / brand_risk_scan / brand_opportunity_scan / brand_recall_check / recall_latest / news_status)
|   `-- weather-server.mjs      # 날씨×코디 축 (weather_now / weather_outfit_brief / weather_status)
|-- naver/                      # ★ 프로덕션 교체 지점 — 어댑터만 내부 API 로 바꾸면 도구 계약 유지
|   |-- core.mjs                # 키 로딩(.env 상향 폴백)·GET/POST·에러 정규화
|   |-- shop.mjs  news.mjs  trend.mjs
|-- kma/weather.mjs             # 기상청 단기예보 어댑터 (격자 20개 도시 + 기온 밴드 옷차림 가이드)
|-- recall/client.mjs           # 소비자24 공식 리콜 DB 어댑터 (XML 자체 파싱, 6품목 메뉴ID)
|-- common/env.mjs              # env 폴백 + 안전 에러 메시지
|-- data/sample/                # 합성 옷장·구매 내역 + 정답지 (README-data.md)
|-- data/templates/             # closet-ops 하네스 상태 파일 템플릿 (원장·규칙·트래킹·사이클로그)
|-- test/
|   |-- run-all.mjs             # 단일 러너 (데이터 정합 + 스모크 4종)
|   |-- closet-data-test.mjs    # 정답지 수치 재계산 검증
|   |-- {shop,trend,news,weather}-smoke.mjs  # MCP 프로토콜 + (키 있으면) 라이브 검증
|   `-- smoke-harness.mjs
`-- bin/
    |-- install-codex.ps1       # 원큐 설치 (멱등)
    `-- run-sample.ps1          # 데모 부트스트랩
```

설계 원칙:

- **zero-dependency**: 심사 환경에서 `npm install` 없이 Node 18+ 만으로 동작.
- **읽기 전용**: 모든 도구는 조회만 한다 (`readOnlyHint` annotation 명시).
- **정직한 데이터**: 수집 커버리지·표본 한계·프록시 여부를 결과 페이로드에 동봉.
- **우아한 강등**: 키 부재 시 스킬은 사용자에게 사실을 직접 묻는 방식으로 계속 동작.
