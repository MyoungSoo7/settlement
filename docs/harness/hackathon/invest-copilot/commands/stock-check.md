---
description: 종목 종합 진단 — 재무 3축 + 거시 + 뉴스/평판을 모아 BUY-8 체크리스트 평가
argument-hint: "<기업명 또는 종목코드>"
---

`buy-sell-criteria` skill 을 로드하고(skill 미지원 환경이면 `invest-copilot/skills/buy-sell-criteria/SKILL.md` 를 직접 읽어라), 대상: $ARGUMENTS 에 대해 조사 절차를 **순서대로** 수행하라:

1. MCP `company_search($ARGUMENTS)` — stockCode 확정 (동명/유사 기업이 여럿이면 사용자에게 확인)
2. MCP `fin_statements(stockCode)` — 최근 2~3개년 원본 (`fundamentals-analysis` 해석 규칙)
3. MCP `fin_metrics(stockCode)` — B1~B5 판정 (서버 계산값 사용)
4. MCP `econ_latest()` + 필요 시 `econ_series()` — B6~B7 (`macro-signals`)
5. MCP `reputation_score` + `news_recent` — B8 (`sentiment-signals` 악재 분류)
6. MCP `invest_signal(stockCode)` — 종합 점수 교차 검증 (내 판정과 다르면 원인 명시)

보고는 buy-sell-criteria 의 보고 형식(결론 한 줄 → 체크리스트 표 → 미충족 해설 →
리스크·분할매수 규칙 → 필수 고지문)을 따르라.
"사라/팔아라" 지시형 금지 — 기준 충족 여부와 수치 근거만 제시한다.
