> RESOLVED by run-2-resume (2026-06-11T19:50:44) — route=exit

# Pending External — ask-back (requirements_update 대기)

- run: run-1
- 시각: 2026-06-11T19:50:30
- route: ask-back
- resume_target: fill_handoff
- pending field: requirements.acceptance.metric
- 사유: 성공 기준이 목표 문장인지 측정 지표인지 미정 (측정 단위·기간 없음) → 회의록 밖 결정 필요

## 2장 질문 구조 (사람 회신 필요)

requirements-contract.md §4의 사각지대 두 질문을 그대로 쓴다. 자동 추측으로 채우지 않는다.

### Q1. 이 정의가 놓친 사용자 시각
- (a) 후속 실무자가 성공 여부를 측정 지표로 추적할 수 있어야 하는가
- (b) "줄인다"의 기준선(현재 문의량)을 함께 봐야 하는가
- (c) CS팀이 집계 기간·집계 기준에 합의해야 하는가

### Q2. 이 정의로 잘려 나간 가능성
- (d) "신청 직후 환불·일정 문의를 줄인다"가 목표 문장인가, 측정 지표인가
- (e) 측정 지표라면 단위(비율·건수)와 기간(예: 4주)을 어디서 가져오는가
- (f) 기준선 수치(CS 문의 60% 추정)는 출처 확정 전까지 지표에 쓸 수 없는가

## 회신 방법

채택(☑/☒)을 inputs/answers.md 형식으로 적은 뒤:

    python3 harness_v2_server.py resume --answers inputs/answers.md

resume 가 requirements.acceptance.metric 과 spec 성공 기준 칸을 갱신(v2 freeze)하고
resume_target(fill_handoff)부터 graph를 이어서 돌린다.
