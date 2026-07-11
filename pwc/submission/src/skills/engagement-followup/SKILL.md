---
name: engagement-followup
description: 엔게이지먼트 사이클의 이행 추적 단계 서브 스킬 — 지난 브리핑의 권고 조치가 실제로 실행됐는지 CEO/담당 팀에 확인하고 액션별 이행 노트를 기록한다. 관리자(ceo-engagement-cycle)가 호출하는 내부 단계.
---

# Engagement Follow-up — 권고 조치 이행 추적

브리핑은 서명으로 끝나지 않는다. 4주 안에 "판별 테스트가 실제로 수행됐는가"를 확인하는 단계.

## 절차

1. `status --engagement <폴더>` 로 액션 목록을 띄우고, 액션마다 이행 인터뷰를 진행한다:
   - "이 판별 테스트/조치를 실행했는가? 결과는? 막힌 이유는?"
   - 답은 반드시 **사실 기술**로 받는다 (예정·의지 표명은 pending 유지).
2. 답변마다 CLI 로 기록한다 — 스킬이 engagement.json 을 직접 편집하지 않는다:
   ```text
   node <ROOT>/bin/engagement-cycle.mjs note --engagement <폴더> \
     --action <id> --status done|in-progress|blocked|pending --note "확인된 사실 한 줄"
   ```
3. blocked 액션은 원인(담당 부재/데이터 없음/의사결정 대기)을 노트에 남기고,
   관리자에게 "브리핑 권고의 실행 가능성 문제"로 별도 보고한다 — 다음 회고의 입력이 된다.
4. 완료 보고: 액션별 상태 표 + "review 게이트(전 액션 노트 1건+) 충족 여부".

## 경계

이 단계는 기록이지 재분석이 아니다 — 수치 재검증은 review 단계(재진단)의 몫.
