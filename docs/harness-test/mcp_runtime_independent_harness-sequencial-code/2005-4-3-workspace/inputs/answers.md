# External Answers — ask-back 회신 (사람 채택)

> 이 파일은 route.ask-back이 `state/pending-external.md`로 남긴 질문에 대한 사람의 회신이다.
> 형식은 2002-3-3 evolve-step의 사각지대 채택(☑/☒)을 그대로 쓴다.
> `harness_v2_server.py resume --answers inputs/answers.md`가 이 채택을 읽어
> requirements.acceptance.metric과 spec 성공 기준 칸을 갱신(v2 freeze)한 뒤 resume_target으로 복귀한다.
>
> 채택이 비어 있으면 requirements_update는 아무것도 갱신하지 않고 blocked exit로 남긴다(자동 추측 금지).

## pending question id

requirements.acceptance.metric

## 채택 (Q1 놓친 시각 / Q2 잘려나간 가능성)

- ☑ (d) 성공 기준을 측정 지표로 본다 (목표 문장이 아니라 지표)
- ☑ (e) 측정 단위 = 신청 직후 환불·일정 문의 비율, 기간 = 다음 회의까지 4주, 목표값 = 15%로 감소
- ☒ (f) 기준선 수치(CS 문의 60% 추정)는 출처 미확인이라 지표 본문에 쓰지 않는다 — 확인 필요 항목에만 유지

## 갱신 결과 (requirements_update가 적용할 값)

- acceptance.metric: "다음 회의까지 4주 동안 신청 직후 환불·일정 문의 비율을 15%로 감소"
- spec 성공 기준 칸(6): 보존 표현 "신청 직후 환불·일정 문의를 줄인다" 인용 유지 + 위 지표 한 줄 병기
- CS 문의 60%: 확인 필요 항목 유지 (출처 미확인)
