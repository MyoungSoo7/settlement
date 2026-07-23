# Handoff — L6 · review 세션 → 메인

## From task (4-1 리프)
- 목적: L6 도메인 제약 위반 검사로 build handoff가 외부 사실 단정, 미확인 수치 단정, 범위 밖 항목의 해결 방향 오염, 미결정 항목의 결론 톤을 만들었는지 검수한다.
- 입력: handoffs/handoff-L3L4-build.md, handoffs/handoff-L5-review.md, inputs/meeting.md, inputs/review-gate.md, inputs/handoff-template.md
- 범위 (하지 않을 일 포함): 본문 6칸과 확인 필요 항목을 다시 쓰지 않는다. 산문 표현 차이는 통과시키고 값 단정·범위 오염·다음 단계 판단에 필요한 모호함만 판정한다.
- 금지: 회의록에 없는 값으로 보정하거나 CS 문의 60%를 출처 확정값처럼 판정하지 않는다.
- 완료조건: Result에 stage별 결과, 위반 항목 줄, verdict, route/다음 행동을 남긴다.

## Result (이 세션이 채운 부분)
- 결과 요약:
  - format: 통과. From task는 handoffs/handoff-L3L4-build.md:3, Result는 handoffs/handoff-L3L4-build.md:10, 검수 대상 구조인 6칸과 확인 필요 항목은 handoffs/handoff-L3L4-build.md:15-21, 근거 줄은 각 칸에 존재, 판정은 handoffs/handoff-L3L4-build.md:23, 다음 행동은 handoffs/handoff-L3L4-build.md:26에 있다.
  - evidence: 통과. 회의 목적과 문의 다수 유입은 meeting.md:5, meeting.md:10, meeting.md:11에 근거가 있고, 해결 방향 두 갈래는 meeting.md:22, meeting.md:23과 일치한다. 범위 밖 항목은 meeting.md:27, meeting.md:28, meeting.md:29와 직접 대조된다.
  - false-negative: 통과. 외부 사실 단정은 없음. CS 문의 60%는 handoffs/handoff-L3L4-build.md:21, handoffs/handoff-L3L4-build.md:25에서 출처 미확인·확인 필요로만 보존되어 meeting.md:11, meeting.md:34와 맞다. 해결 방향은 handoffs/handoff-L3L4-build.md:19에서 두 갈래만 제시되어 meeting.md:22, meeting.md:23을 넘지 않는다. 미결정 항목은 handoffs/handoff-L3L4-build.md:21에서 확인 필요로 보존되어 meeting.md:33, meeting.md:34, meeting.md:35, meeting.md:36과 맞다. 성공 기준은 사용자 회신으로 `다음 회의까지 4주 동안 신청 직후 환불·일정 문의 비율을 15%로 감소`로 좁혀졌다.
  - 위반 항목 줄: 값 단정·범위 오염 위반 없음.
  - verdict: 통과
  - route/다음 행동: main 세션에서 7칸 1page 초안으로 통합한다.
- 판정: 통과
- 남은 질문: 없음
- 다음 세션의 첫 행동: main 세션에서 7칸 1page 초안으로 통합한다.
