# 환불 정산 Append-only 수정 설계

## 목표

환불이 발생해도 원 정산의 금융 스냅샷을 수정하지 않고 조정 레코드를 추가해 유효 정산액을 계산한다. 전액 환불은 유효 결제액·수수료·순정산액이 모두 0이 되어야 하며, 부분 환불은 정산 생성 당시 `commission_rate`와 `HALF_UP` 라운딩을 적용한다.

## 확인된 결함

- 현재 `Settlement.adjustForRefund`는 `paymentAmount - refundedAmount - originalCommission`으로 계산해 10,000원 NORMAL 전액 환불을 `-350원`으로 만든다.
- `AdjustSettlementForRefundService`가 원 정산의 환불액·순정산액·홀드백을 변경한 뒤 조정 row도 추가하므로 이력 불변 규칙과 충돌한다.
- 조정 row는 gross 환불액, 원장 역분개는 gross를 순액과 수수료 반환으로 분해한다. 조회·확정 배치는 조정을 합산하지 않아 세 계층의 의미가 일치하지 않는다.
- 레거시 누적 환불 이벤트의 delta 계산이 원 정산 `refundedAmount`에 의존하므로 append-only 전환 시 조정 합계를 사용해야 한다.

## 선택한 접근

기존 `settlement_adjustments.amount`의 의미는 gross 환불액의 음수로 유지한다. 이에 따라 기존 `|adjustments| = refunds` 대사 불변식과 마이그레이션·인덱스를 보존하며 신규 스키마 변경은 하지 않는다.

환불 한 건의 유효 금액은 다음과 같다.

```text
refundCommission = round(refundGross * snapshotCommissionRate, 2, HALF_UP)
refundNet        = refundGross - refundCommission
```

정산 전체의 유효 금액은 환불 건별 계산 후 합산한다.

```text
effectiveRefunded  = sum(refundGross_i)
effectiveCommission = originalCommission - sum(refundCommission_i)
effectiveNet        = originalNet - sum(refundNet_i)
```

각 결과는 0 미만이 될 수 없으며 누적 gross 환불은 원 결제액을 초과할 수 없다. 10,000원 NORMAL 전액 환불은 수수료 반환 350원, 순액 조정 9,650원, 유효 수수료·순정산액 0원이다.

## 구성 요소와 데이터 흐름

1. `SettlementAdjustment`는 gross 환불액 음수 row를 생성한다. 원 정산의 금융 필드는 변경하지 않는다.
2. 조정 조회 포트는 정산별 환불 조정 목록 또는 합계와 `refundId` 존재 여부를 제공한다.
3. 도메인 유효 금액 계산기는 원 정산 스냅샷과 환불 조정을 받아 유효 환불액·수수료·순액·파생 상태를 반환한다.
4. 환불 서비스는 정산을 잠금 조회해 누적 상한과 중복을 검증하고, 조정 INSERT와 원장 outbox INSERT만 같은 트랜잭션에서 수행한다. `DONE` 정산도 동일하게 append-only 조정과 역분개를 허용한다.
5. Kafka 레거시 누적 이벤트의 delta는 원 정산 row가 아니라 기존 환불 조정 gross 합계에서 계산한다.
6. 정산 단건 조회와 감사 조회는 유효 금액을 반환한다. 원본 스냅샷을 감사 목적으로 보존하되 기존 API의 `commission`, `netAmount`, `status`는 유효 값을 표시한다.
7. 확정 배치는 유효 금액을 사용한다. 유효 순액이 0인 전액 환불 정산은 파생 `CANCELED`로 보고 확정·payout·원장 CREATE 대상에서 제외한다.
8. 부분 환불 후 확정하는 경우 ledger CREATE는 유효 gross·commission·net을 사용해 `effectiveGross = effectiveCommission + effectiveNet`을 만족한다.
9. 환불 원장 역분개는 현재처럼 양수 금액 row와 차/대 반전을 유지하되 수수료율을 `commission/payment`로 재추론하지 않고 원 정산의 스냅샷 `commissionRate`를 사용한다.

## 호환성과 오류 처리

- 기존 조정 row의 gross 의미, 환불 대사 리포트, `refund_id` 유니크 인덱스를 유지한다.
- 동일 `refundId` 재수신은 기존 조정을 확인하고 무해하게 건너뛴다.
- 누적 환불이 결제액을 초과하면 전체 트랜잭션을 실패시켜 조정과 ledger outbox가 모두 기록되지 않게 한다.
- `refundId`가 없는 레거시 이벤트도 기존 동작대로 조정을 허용하지만, 누적 합계 기반 delta가 0이면 건너뛴다.
- 이미 잘못 UPDATE된 과거 정산 row의 자동 복구는 이번 변경 범위에 포함하지 않는다. 신규 환불부터 원본을 보존하며 과거 데이터 정정은 별도 조정·재구축 절차로 다룬다.

## 테스트 전략

- RED: 10,000원 NORMAL 전액 환불의 유효 commission/net/status가 `0/0/CANCELED`인지 검증한다.
- RED: 부분 환불 여러 건에서 건별 `HALF_UP` 후 합산되는지 검증한다.
- RED: 환불 서비스가 `SaveSettlementPort`를 호출하지 않고 adjustment와 ledger outbox만 기록하는지 검증한다.
- RED: `DONE` 정산 환불이 거부되지 않고 append-only 조정을 생성하는지 검증한다.
- RED: 동일 `refundId`와 레거시 누적 이벤트가 중복 조정을 만들지 않는지 검증한다.
- 통합: PaymentRefunded 소비 후 원 정산 금융 컬럼은 불변이고 adjustment가 1건 생성되는지 검증한다.
- 통합: 부분 환불 후 확정 원장과 환불 역분개의 금액 분해·차대 균형을 검증한다.
- 회귀: settlement 전체 테스트와 금액/원장/정합성 테스트를 실행한다.

## 범위 제외

- 운영·스테이징 DB 직접 보정
- 과거 음수 net 정산의 자동 데이터 마이그레이션
- chargeback·PG reconciliation 조정 의미의 재설계
- REST 응답에 원본과 유효 금액을 동시에 노출하는 신규 버전 API
