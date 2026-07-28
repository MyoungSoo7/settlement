---
description: 매수 전 최종 체크리스트 — BUY-8 자동 판정 + 분할 매수 계획 수립
argument-hint: "<기업명 또는 종목코드> [투자예정금액]"
---

`buy-sell-criteria` 와 `risk-management` skill 을 로드하라(skill 미지원 환경이면
`invest-copilot/skills/buy-sell-criteria/SKILL.md`, `invest-copilot/skills/risk-management/SKILL.md` 를 직접 읽어라). 대상: $ARGUMENTS

1. /stock-check 와 동일한 조사 절차로 BUY-8 을 판정하라 (하드 필터 우선: 적자·부채비율 200%·자본잠식·회계/법적 악재)
2. 판정이 "적극 검토" 또는 "조건부"인 경우에만 분할 매수 계획을 제시하라:
   - 3-3-4 분할 (30%→30%→40%), 2·3차 진입 조건
   - 투자예정금액이 주어졌으면 종목당 비중 10% 상한 확인 질문 (전체 자산 대비)
   - 손절선 -7% 를 매수 전에 먼저 약속하게 하라 ("얼마에 팔지 모르면 사지 않는다")
3. "보류" 판정이면 매수 계획을 만들지 말고, 재평가 트리거(어떤 수치가 바뀌면)를 제시하라

가격(현재가·호가)은 이 플랫폼에 없다 — 매수가·수량 계산은 HTS/MTS 확인 항목으로 구분하라.
출력 끝에 필수 고지문 포함.
