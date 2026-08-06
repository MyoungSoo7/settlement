---
description: 매도/손절 판단 체크리스트 — 데이터 신호(S1~S4) 자동 판정 + 가격 기준(S5~S7) 확인 질문
argument-hint: "<기업명 또는 종목코드> [매수가/수익률 등 보유 정보]"
---

`buy-sell-criteria` skill 을 로드하라(skill 미지원 환경이면 `invest-copilot/skills/buy-sell-criteria/SKILL.md` 를 직접 읽어라). 대상: $ARGUMENTS

1. **데이터 신호 (자동)**: `fin_metrics`(S1 펀더멘털 훼손·S2 부채 급증) →
   `reputation_score`/`news_recent`(S3 평판 악재) → `econ_latest`/`econ_series`(S4 거시 악화)
2. **가격 신호 (질문)**: 보유 정보가 없으면 매수가·현재 수익률을 물어라.
   S5(-7% 손절선)·S6(+20~30% 분할 익절)·S7(매수 논거 소멸) 을 함께 점검한다.
3. S1~S3 해당 시 **물타기 금지**를 명시하고, 해당 신호의 원인(수치·기사)을 인용하라.
4. 결론은 "해당하는 매도 기준 N개 / 해당 없음" 형식 — "팔아라" 지시형 금지.

손절은 감정이 아니라 규칙임을 상기시켜라 (`risk-management` -7% 기계적 손절).
출력 끝에 필수 고지문 포함.
