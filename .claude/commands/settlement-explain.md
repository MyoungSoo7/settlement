---
description: 정산 1건의 계산 근거를 사람이 읽을 수 있게 풀이 (CS/셀러 문의 대응)
argument-hint: "<settlementId 또는 '금액 등급' (예: 1000000 VIP)>"
---

`settlement-domain-rules` skill 을 로드하라. 대상: $ARGUMENTS

- settlementId 가 주어지면: MCP `ledger_entries` 로 해당 정산의 분개를 찾고
  (`/api/ledger/settlements/{id}` 형태 조회가 가능하면 그것을 우선),
  저장된 commission_rate **스냅샷**을 기준으로 설명하라 (현재 등급 요율로 재계산 금지).
- "금액 등급" 형태면: MCP `settlement_simulate(amount, tier)` 로 계산하라.

출력 형식 (비개발자도 읽을 수 있게):

```
주문금액        1,000,000원
수수료 (3.5%)    -35,000원   ← 정산 시점 요율 스냅샷
정산금           965,000원
홀드백 (30%)    -289,500원   ← 30일(영업일) 후 자동 해제
즉시 지급        675,500원
지급 예정일      T+7 영업일
```

환불이 낀 건이면 조정(역정산) 내역을 별도 줄로 분리해 "원 정산 − 조정 = 최종" 구조로 보여줘라.
