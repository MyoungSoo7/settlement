---
description: 계정계 GL 시산표 검증 + 차대 균형·분개 매핑 정합 조사
argument-hint: "[ownerType/ownerId 또는 전표 조회 조건, 생략 시 전체 시산표]"
---

`account-domain-rules` 와 `ledger-invariants` skill 을 로드하라. 대상: $ARGUMENTS

1. account-service 가 실행 중이면 시산표 API `/api/account/trial-balance` 를 조회해 `balanced` 를 확인하라.
   owner 범위가 주어지면 `/api/account/accounts/{ownerType}/{ownerId}` 잔액,
   집계 문의면 `/api/account/aggregates/loans` 등 집계 API 를 함께 본다.
2. **균형(`totalDebit == totalCredit`)이면**: 계정별 차변합/대변합 라인과 분개 수 요약만 보고하고 종료.
3. **불균형이면** — 각 전표가 구성적 균형(차1·대1·금액1)이라 정상적으로는 절대 깨지지 않는다.
   `false` 는 **데이터 손상 신호**다. 다음을 조사하라:
   - **단일 row(한쪽 계정만) 삽입** 경로가 생겼는지 — 팩토리 6종을 우회한 코드.
   - **음수·0 전표**가 삽입됐는지 (팩토리는 양수만 허용 — 우회 여부).
   - 이벤트→분개 매핑이 skill 의 6종 표(DR/CR)와 어긋나는지 (컨슈머 인라인 조립 드리프트).
   - `loan.repayment_applied` 의 `deducted=0` 건에 억지 0원 전표가 들어갔는지.

보고: 위반 유형별 분류 + 원인 전표의 `(source_topic, ref_type, ref_id)` 자연키를 제시하고,
정정은 **역분개 추가**로만 제안하라 (UPDATE/DELETE 및 단일 row 보정 제안 금지). 계정계는 소비 전용 —
발행 코드 추가를 해법으로 제안하지 마라.
