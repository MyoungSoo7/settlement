# ADR 0015 — 정산 보류 (Holdback) — 등급별 차등 + 자동 해제

- 상태: Accepted
- 일자: 2026-04-28

## 컨텍스트

마켓플레이스 정산의 핵심 리스크: **신뢰도 낮은 셀러의 환불 다발 + 사기**. 정산금 지급 후
대량 환불이 발생하면 운영사가 셀러로부터 회수해야 하는데 — 회수 실패율이 높다.

보편적 해법: 정산금 일부를 일정 기간 보류했다가 분쟁 없으면 풀어주는 **Holdback (예치)**.

## 결정

### 등급별 보류 정책

| 등급 | 보류율 | 보류 기간 | 의미 |
|------|--------|-----------|------|
| **NORMAL** | 30% | 30 영업일 | 기본 — 환불 흡수 안전 마진 |
| **VIP** | 10% | 14 영업일 | 신뢰도 검증된 셀러 |
| **STRATEGIC** | 0% | 0 일 | 전략 파트너 — 즉시 전액 정산 |

### 핵심 정책 — 환불 시 holdback 우선 차감

```
정산: net=96,500, holdback=28,950 (30%)
   → 즉시 셀러 출금 가능: 67,550
환불 10,000 발생:
   → holdback 에서 우선 차감 (10,000)
   → holdback 잔액: 18,950
   → 셀러 net 영향 없음 ★
환불 50,000 발생 (보류 초과):
   → holdback 전액 28,950 차감
   → 보류금 소진 → 자동 released
   → 잔여 21,050 은 일반 환불 흐름 (셀러 net 차감)
```

이 정책의 효과:
- 신뢰도 낮은 셀러의 환불을 *보류금 안에서 흡수* 하여 운영사 회수 부담 0
- 환불 없이 release_date 도달 시 자동 해제 → 셀러 출금 가능

### 자동 해제 배치

`HoldbackReleaseScheduler` 가 매일 03:00 KST `releaseAllDueOn(today)` 호출.
- release_date <= today 인 미해제 정산을 100 건씩 페이지 처리
- `Settlement.releaseHoldback()` 도메인 메서드가 상태 전이 + 시각 기록
- `settlement.holdback.released` Prometheus 카운터로 모니터링

## 결과

- `Settlement.applyHoldback(rate, releaseDate)` — 정산 생성 시 자동 적용
- `Settlement.consumeHoldbackForRefund(amount)` — 환불 시 우선 차감
- `Settlement.getImmediatePayoutAmount()` — 셀러 즉시 출금 가능액 계산
- 단위 테스트 13건 (HoldbackPolicy 4 + Settlement 9)

### 향후 확장

- 셀러별 동적 holdback 율 (FDS 환불률·분쟁률 기반)
- 분쟁 진행 중 holdback 풀지 않기 (분쟁 도메인 추가 후)
- 보류 해제 알림 (셀러 대시보드 푸시)

## 대안

- **단일 고정 보류율**: 등급별 차등 없이 모두 동일. 좋은 셀러에게도 자금 회전 부담
- **별도 holdback 테이블**: 정산과 1:1 인데 굳이 분리 — JOIN 오버헤드만 생김
- **수동 해제만**: 운영자 부담 큼 + 사고 위험 (해제 누락)

## 참조

- [V42](../../order-service/src/main/resources/db/migration/V42__settlement_holdback.sql)
- [HoldbackPolicy.java](../../settlement-service/src/main/java/github/lms/lemuel/settlement/domain/HoldbackPolicy.java)
- [ReleaseHoldbackService.java](../../settlement-service/src/main/java/github/lms/lemuel/settlement/application/service/ReleaseHoldbackService.java)
- [SettlementHoldbackTest.java](../../settlement-service/src/test/java/github/lms/lemuel/settlement/domain/SettlementHoldbackTest.java)
