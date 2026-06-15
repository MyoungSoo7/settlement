# 락 전략 적용 및 영향도 분석 (Locking Strategy & Impact Analysis)

> 대상 브랜치: `develop` · 작성일: 2026-06-06
> 범위: 돈·재고·선착순 흐름의 동시성/멱등성 취약점 5건 보강

---

## 0. 요약

Lemuel(주문·결제·정산 MSA)의 자금 이동 경로에서 **lost update / 이중 실행 / 중복 INSERT** 가능성이 있던
5개 지점에 락 또는 멱등 제약을 적용했다. 각 지점마다 부하·정합성·외부 부수효과(PG 호출) 특성이 다르므로
**비관적 락 / 원자적 SQL 클레임 / DB UNIQUE 제약**을 선택적으로 적용했다.

| # | 영역 | 위협 | 적용 기법 | 비고 |
|---|------|------|-----------|------|
| 1 | 결제 환불 | lost update + PG 이중 호출 | 비관적 락 (`SELECT ... FOR UPDATE`) | 외부 부수효과 → 낙관적 락 부적합 |
| 2 | 원장 분개 | 중복 분개 INSERT | DB UNIQUE INDEX | 소프트 체크 + 폴러 재시도와 결합 |
| 3 | 정산 지급(Payout) | 이중 송금 | 원자적 SQL 클레임 (조건부 UPDATE) | save 재조회가 `@Version` 무력화 → 클레임으로 대체 |
| 4 | 쿠폰 1인 1매 | 동시 중복 사용 | DB UNIQUE + 우아한 예외 변환 | 제약은 기존 존재, 핸들링 추가 |
| 5 | 일일 정산 확정 | 이중 확정 + 이중 원장 적재 | 비관적 락 (상태+일자 범위) | 배치/수동 트리거 직렬화 |

---

## 1. 결제 환불 — 비관적 락

### 위협
환불은 `payments` 행을 읽어 금액을 차감하고 PG(Toss)에 환불을 요청한다. 동시 환불 요청 2건이 같은 행을
잠그지 않고 읽으면 **lost update** 가 발생하고, 더 심각하게는 **PG 환불 API가 두 번 호출**되어 실제 자금이
이중 환불될 수 있다.

### 적용
- `PaymentJpaRepository.findByIdForUpdate` — `@Lock(PESSIMISTIC_WRITE)` + JPQL
- `LoadPaymentPort.loadByIdForUpdate` 포트 추가, `PaymentPersistenceAdapter` 구현
- `RefundPaymentUseCase`, `RefundSplitPaymentService` 의 로드 호출을 `loadByIdForUpdate` 로 교체

### 왜 비관적 락인가
`@Version` 낙관적 락은 충돌 시 예외 후 **재시도**를 전제로 한다. 그러나 환불은 PG 호출이라는 **되돌릴 수 없는
외부 부수효과**를 포함하므로, 충돌을 감지한 시점엔 이미 한 번 호출됐을 수 있다. 비관적 락으로 두 번째 트랜잭션을
**진입 자체에서 대기**시켜 직렬화하는 것이 안전하다. 부모(payment) 행 락이 자식 tender 갱신까지 전이적으로 직렬화하므로
`PaymentTender` 에 별도 `@Version` 은 추가하지 않았다.

### 영향도
- **성능**: 같은 paymentId 에 대한 동시 환불만 직렬화. 서로 다른 결제는 영향 없음. 환불은 저빈도라 처리량 영향 미미.
- **데드락**: 단일 행 락 + 짧은 트랜잭션이라 위험 낮음. 분할결제는 부모 행 하나만 잠금.
- **반드시 `@Transactional` 컨텍스트 안에서 호출** (포트 javadoc 명시).
- **테스트**: `RefundPaymentUseCaseTest` 스텁 9곳을 `loadByIdForUpdate` 로 변경. 타 결제 유스케이스 테스트는 `loadById` 유지(정상).

---

## 2. 원장 분개 — DB UNIQUE 제약

### 위협
정산 확정 → 원장 분개(ledger entry) 생성 경로는 Outbox 폴러가 재시도할 수 있다. 소프트 체크
(`existsByReference`)만으로는 동시 폴러/재시도 시 **중복 분개**가 INSERT 될 수 있어 회계 잔액이 틀어진다.

### 적용
`order-service/.../db/migration/V20260606120000__ledger_reference_unique.sql`:
```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_reference_accounts
    ON opslab.ledger_entries (reference_id, reference_type, debit_account, credit_account);
```

### 왜 4개 컬럼 복합 UNIQUE 인가
하나의 정산은 **2개의 분개 행**을 만든다 — (ACCOUNTS_PAYABLE/REVENUE) 와 (COMMISSION_EXPENSE/COMMISSION_REVENUE).
`reference_id + reference_type` 만으로 UNIQUE 를 걸면 정상적인 두 번째 행이 거부된다. 계정 쌍(debit/credit)까지
포함해야 "같은 참조의 같은 분개"만 중복으로 차단된다.

### 영향도
- **코드 변경 없음**: 기존 `existsByReference` 소프트 체크는 정상 경로 최적화로 유지. DB 가 최종 방어선.
- **재시도 흐름**: 중복 INSERT 는 DB 가 거부 → `LedgerOutboxPoller` 가 멱등하게 처리(이미 적재됨으로 간주).
- **마이그레이션 안전성**: `IF NOT EXISTS` 라 재실행 안전. 기존 중복 데이터가 있으면 인덱스 생성이 실패할 수 있으므로 배포 전 중복 점검 권장.

---

## 3. 정산 지급(Payout) — 원자적 SQL 클레임

### 위협
`PayoutService.executeAllPending` 가 REQUESTED 상태 payout 을 SENDING 으로 전이시키고 송금한다.
동시 배치 인스턴스(또는 ShedLock 경계 밖 재실행)가 같은 payout 을 집어 **이중 송금**할 수 있다.

### 핵심 발견
`PayoutPersistenceAdapter.save()` 는 엔티티를 `findById` 로 **재조회한 뒤** 도메인 상태를 덮어쓴다.
이 재조회 때문에 `@Version` 낙관적 락이 **신뢰성 있게 발동하지 않는다** — 패자 트랜잭션이 완료된 payout 을
덮어쓸 수 있다. 따라서 단순 `@Version` 의존은 불충분.

### 적용
조건부 원자적 UPDATE 로 "클레임"한다:
```java
@Modifying(clearAutomatically = true)
@Query("UPDATE PayoutJpaEntity p SET p.status = SENDING, p.sentAt = :now, " +
       "p.version = p.version + 1, p.updatedAt = :now " +
       "WHERE p.id = :id AND p.status = REQUESTED")
int claimForSending(Long id, LocalDateTime now);
```
- `SavePayoutPort.claimForSending` 추가, 어댑터가 `affected==0 → Optional.empty()` 반환
- `PayoutSingleExecutor` 가 `claimForSending(...).orElseThrow(PayoutConcurrentClaimException::new)`
- `PayoutService` 가 `PayoutConcurrentClaimException | OptimisticLockingFailureException` 을
  **별도 catch** 하여 `payout.conflict` 메트릭 증가 + warn 로그, 배치는 계속 진행

### 왜 원자적 클레임인가
`WHERE status = REQUESTED` 조건으로 **DB 레벨에서 단 한 트랜잭션만** REQUESTED→SENDING 전이를 성공시킨다.
패자는 affected row 0 → 송금 코드에 진입조차 못 한다. 재조회 패턴과 무관하게 안전.

### 영향도
- **성능**: 인덱스(id PK) 기반 단건 UPDATE 라 부하 미미. 충돌 시 송금 스킵이므로 배치 멈춤 없음.
- **관측성**: `payout.conflict` Counter 로 동시 충돌 빈도 모니터링 가능.
- **부분 실패 격리**: 한 payout 충돌이 배치 전체를 중단시키지 않음(개별 catch).

---

## 4. 쿠폰 1인 1매 — DB UNIQUE + 우아한 예외 변환

### 위협
`validateCoupon` 의 `hasUserUsedCoupon` 는 **소프트 체크**라 동시 요청을 막지 못한다. 같은 사용자가 동시에
같은 쿠폰을 사용하면 두 INSERT 가 모두 통과해 1인 1매 한도를 우회할 수 있다.

### 적용
DB 제약은 이미 존재(`V20__create_coupons_table.sql`):
```sql
CONSTRAINT uq_coupon_usage_user UNIQUE (coupon_id, user_id)
```
`CouponService.useCoupon` 에 우아한 핸들링 추가:
```java
try {
    saveCouponPort.recordUsage(coupon.getId(), userId, orderId);
} catch (DataIntegrityViolationException e) {
    log.warn("쿠폰 중복 사용 차단: code={}, userId={}", code, userId);
    throw new IllegalStateException("이미 사용한 쿠폰입니다.", e);
}
```

### 왜 이 방식인가
`coupon_usages` 가 IDENTITY 전략이라 INSERT 가 **즉시** 실행되어 제약 위반이 동기적으로 표면화된다.
위반 시 전체 트랜잭션이 롤백되므로 앞서 증가시킨 `used_count` 도 함께 취소된다(정합성 유지). 사용자에겐
멱등하게 "이미 사용한 쿠폰" 으로 응답.

### 영향도
- **마이그레이션 없음**: 제약·엔티티 선언 모두 기존 존재. 예외 변환 핸들링만 추가.
- **UX**: 원시 DB 예외 대신 도메인 메시지로 변환 → 깔끔한 에러 응답.
- **정합성**: `used_count` 증가와 usage INSERT 가 같은 트랜잭션이라 함께 롤백.

---

## 5. 일일 정산 확정 — 비관적 락 (상태+일자 범위)

### 위협
`ConfirmDailySettlementsService` 가 특정 일자의 정산을 REQUESTED→DONE 으로 확정하고 원장 작업을 적재한다.
스케줄 배치(ShedLock)와 운영자 수동 트리거가 같은 일자를 동시에 확정하면 **이중 확정 + 이중 원장 적재**가 발생.

### 적용
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM SettlementJpaEntity s " +
       "WHERE s.settlementDate = :settlementDate AND s.status = :status " +
       "ORDER BY s.id ASC")
List<SettlementJpaEntity> findBySettlementDateAndStatusForUpdate(LocalDate settlementDate, String status);
```
- `LoadSettlementPort.findConfirmableForUpdate` 추가, 어댑터가 REQUESTED 상태로 호출
- `ConfirmDailySettlementsService` 가 `findBySettlementDate` → `findConfirmableForUpdate` 로 교체
- 조회 결과는 모두 REQUESTED 이나 **방어적 `isPending()` 재확인** 유지

### 왜 비관적 락인가
두 번째 트랜잭션은 이 락에서 **대기**하다 첫 번째 커밋 후 진행한다. 그 시점엔 행이 이미 DONE 이라
REQUESTED 필터에서 빠져 결과 집합이 비고, 이중 확정·이중 원장 적재가 원천 차단된다.
**데드락 회피**를 위해 `ORDER BY id ASC` 로 결정적(deterministic) 잠금 순서를 강제.

### 영향도
- **성능**: 일자 단위 배치라 저빈도. 같은 일자 동시 확정만 직렬화.
- **데드락**: id 오름차순 결정적 잠금으로 회피.
- **반드시 `@Transactional` 안에서 호출** (포트/리포지토리 javadoc 명시).
- **테스트**: `ConfirmDailySettlementsServiceTest` 3개 스텁을 `findConfirmableForUpdate` 로 변경.
  `skipsAlreadyDone` 은 방어적 `isPending` 가드 검증으로 의미 재정의(락 조회가 DONE 을 반환해도 스킵).

---

## 6. 횡단 관심사 (Cross-cutting)

### 락 기법 선택 기준
| 상황 | 권장 기법 | 근거 |
|------|-----------|------|
| 되돌릴 수 없는 외부 부수효과(PG) | 비관적 락 | 충돌 후 재시도 불가 → 진입 직렬화 |
| 재조회-후-저장 패턴으로 `@Version` 무력화 | 원자적 조건부 UPDATE | DB가 단일 승자 보장 |
| 중복 INSERT 멱등성 | DB UNIQUE 제약 | 최종 방어선, 코드 경합과 무관 |
| 저빈도 배치 범위 확정 | 비관적 락(범위) | 직렬화 비용 < 정합성 가치 |

### 공통 주의사항
- 비관적 락 메서드는 **모두 `@Transactional` 컨텍스트** 필요(javadoc 명시).
- 범위 락은 **결정적 정렬(`ORDER BY id`)** 로 데드락 회피.
- DB 제약 추가 시 **기존 중복 데이터 점검** 후 배포(인덱스 생성 실패 방지).
- 충돌은 **메트릭으로 관측**(`payout.conflict`) 하여 경합 빈도를 모니터링.

### 신규 마이그레이션
- `V20260606120000__ledger_reference_unique.sql` (Fix 2) — 신규
- Fix 4 는 기존 제약 활용, Fix 1/3/5 는 스키마 변경 없음(코드/쿼리 레벨)
