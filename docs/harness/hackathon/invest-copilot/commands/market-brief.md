---
description: 시장 환경 브리핑 — 4대 경제지표 순회 후 시장 온도(🟢/🟡/🔴) 판정 + 초보자 행동 기준
---

`macro-signals` skill 을 로드하고(skill 미지원 환경이면 `invest-copilot/skills/macro-signals/SKILL.md` 를 직접 읽어라), 다음 순서로 시장 환경을 브리핑하라:

1. MCP `econ_latest()` — 기준금리·국고채3년·USD/KRW·CPI 최신값 (기준일 필수 확인)
2. MCP `econ_series(지표, 6개월)` — 기준금리·국고채3년은 반드시 추이로 사이클 판정
3. macro-signals 의 시장 온도 표로 🟢 우호 / 🟡 중립 / 🔴 비우호 판정 — 근거 수치·기준일 병기
4. 판정별 초보자 행동 기준 제시 (🔴 이면 분할 매수 1차 30% 제한 등, `risk-management`)

데이터 기준일이 1개월 이상 오래됐으면 "데이터 시점 한계"를 판정과 함께 명시하라.
서비스가 내려가 도구 호출이 실패하면 그것 자체를 보고하라 — 수치를 추측으로 채우지 마라.
출력 끝에 compliance-language 의 필수 고지문을 포함하라.
