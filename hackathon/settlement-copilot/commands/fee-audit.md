---
description: 수수료·홀드백 코드 감사 — 도메인 정책·머니 세이프티 기준 + simulate 교차검증
argument-hint: "[파일 경로 또는 PR 번호, 생략 시 현재 브랜치 diff]"
---

`settlement-domain-rules` 와 `money-safety` skill 을 로드하라
(skill 미지원 환경이면 `settlement-copilot/skills/settlement-domain-rules/SKILL.md` 와
`settlement-copilot/skills/money-safety/SKILL.md` 를 직접 읽어라). 감사 대상: $ARGUMENTS
(비어 있으면 `git diff origin/develop...HEAD` 중 수수료/홀드백/정산 계산 관련 파일).

검사 항목:

1. **타입**: 금액에 float/double/parseDouble 사용 여부, BigDecimal 문자열 생성자 여부
2. **라운딩**: divide/setScale 의 RoundingMode 명시 여부 (표준 HALF_UP)
3. **정책 일치**: 요율(NORMAL 0.0350 / VIP 0.0250 / STRATEGIC 0.0200), 홀드백(30%/30d, 10%/14d, 0%),
   계산 순서(수수료 차감 → net → 홀드백)가 SellerTier/HoldbackPolicy 와 일치하는지
4. **스냅샷**: 정산 생성 시 commission_rate 스냅샷 저장 여부, 과거 정산 재계산 코드 부재
5. **교차검증**: 대표 금액 3개(1,000,000 / 라운딩 경계값 / 1원)에 대해 MCP `settlement_simulate` 로
   기대값을 뽑아 코드의 계산 결과·테스트 기대값과 대조

발견 항목은 심각도(BLOCK/WARN/INFO)와 파일:라인, 수정 제안 코드와 함께 보고하라.
