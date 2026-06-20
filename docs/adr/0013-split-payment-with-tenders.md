# ADR 0013 — 분할결제 (PaymentTender) + 역순 환불 정책

- 상태: Accepted
- 일자: 2026-04-28

## 컨텍스트

거의 모든 한국 이커머스가 1 주문에 여러 지불수단을 동시에 사용한다 — 멤버십 포인트 + 상품권 +
신용카드 조합이 대표적. 단일 `payments.amount` 만 추적하던 구조는 다음을 표현 불가:

- 어떤 수단으로 얼마를 지불했는지 (영수증·정산 분배·환불 추적)
- 환불 시 어느 수단부터 빼야 하는지 정책

## 결정

`PaymentTender` 1:N 자식 도메인 도입. 결제 1 건 = N 개 tender. `SUM(tenders.amount) ==
payment.amount` 가 도메인 불변식.

### 환불 정책 — 역순 (sequence DESC)

```
[Tender seq=1: POINT 5,000]
[Tender seq=2: GIFT_CARD 10,000]
[Tender seq=3: CARD 35,000]
환불 30,000 요청 → CARD 부터 차감 (30,000)
환불 40,000 요청 → CARD 35,000 + GIFT_CARD 5,000
환불 50,000 요청 → 전액 역순 분배
```

### 왜 역순인가

외부 PG (CARD/KAKAO_PAY) 가 먼저 취소되어야 실 거래가 사라진다. 만약 내부 잔액
(POINT/GIFT_CARD) 을 먼저 환불했는데 PG 환불이 실패하면, 내부 잔액은 복원되었지만
카드 거래는 살아있는 *부분 정합성 깨짐* 상태가 된다.

역순으로 처리하면:
- PG 환불 실패 시 → 내부 잔액은 안 건드린 채로 운영자 알람 → 수동 대응
- PG 환불 성공 후 내부 잔액 복원 실패 → 운영자가 잔액만 수동 복원 (덜 위험)

### 도메인 메서드

```java
PaymentDomain.createSplit(orderId, tenders, label)  // 합계 자동 계산
PaymentDomain.planRefundFromTenders(amount)          // 역순 환불 계획 (도메인 순수)
PaymentDomain.validateTenderSum()                    // 불변식 검증
```

## 결과

- 운영자가 영수증·정산 분배·환불 추적 모두 가능
- `RefundSplitPaymentService` 가 도메인 plan 받아 PG/내부잔액 차등 처리
- 도메인 단위 테스트 16건 (PaymentTender 7 + SplitPayment 9)

## 대안

- **결제 1 건당 row N 개**: payments 테이블을 비정상화. 정산·통계·검색 모두 깨짐
- **JSON 컬럼에 tender 목록**: 쿼리 불가, 인덱스 안 먹음
- **선입선출 환불**: 외부 PG 가 마지막에 환불되어 위 정합성 깨짐 위험

## 참조

- [V41](../../order-service/src/main/resources/db/migration/V41__payment_tenders.sql)
- [PaymentTender.java](../../order-service/src/main/java/github/lms/lemuel/payment/domain/PaymentTender.java)
- [SplitPaymentDomainTest.java](../../order-service/src/test/java/github/lms/lemuel/payment/domain/SplitPaymentDomainTest.java)
