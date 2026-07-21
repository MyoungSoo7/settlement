package github.lms.lemuel.settlement.domain;

import github.lms.lemuel.settlement.domain.exception.NegativeAdjustmentAmountException;
import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 뮤테이션 갭 테스트 — PIT 베이스라인에서 살아남은(SURVIVED) 뮤턴트 16건을 정조준해 제거한다.
 *
 * <p>각 테스트는 "테스트가 코드를 실행은 하지만 assert 가 얄팍해 못 잡던" 경계·가드 분기를 겨눈다.
 * 크게 두 부류:
 * <ul>
 *   <li><b>경계값(ConditionalsBoundary)</b>: {@code <=} vs {@code <}, {@code >} vs {@code >=} 의 경계 입력
 *       (paymentId=0, rate=0/1, holdback=0, clawback=0 등)을 직접 넣어 경계 이동을 관측.</li>
 *   <li><b>가드 거부/폴백(NegateConditionals)</b>: 생성자 null 폴백 정규화가 non-default 입력을
 *       그대로 보존하는지, 상태 플래그 분기가 뒤집히면 결과가 달라지는지 관측.</li>
 * </ul>
 */
class DomainMutationGapTest {

    // ===== Settlement 생성자 null 폴백 정규화 (L80·84·88·89·91·92, NegateConditionals) =====

    @Test
    @DisplayName("[gap] rehydrate 는 non-default 필드를 폴백으로 덮지 않고 그대로 보존한다")
    void rehydratePreservesNonDefaultFields() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        LocalDateTime updated = LocalDateTime.of(2026, 6, 7, 8, 9, 10);

        Settlement s = Settlement.rehydrate(
                7L, 100L, 200L,
                new BigDecimal("10000"),      // paymentAmount
                new BigDecimal("500"),        // refundedAmount (non-zero → L80 폴백 부정 뮤턴트 제거)
                new BigDecimal("300"),        // commission
                new BigDecimal("0.025"),      // commissionRate
                new BigDecimal("9200"),       // netAmount
                SettlementStatus.DONE,        // status (non-default → L84)
                LocalDate.of(2026, 1, 1),     // settlementDate
                null, null,
                created,                      // createdAt (non-null → L88)
                updated,                      // updatedAt (non-null → L89)
                3L,
                new BigDecimal("2760"),       // holdbackAmount (non-zero → L91)
                new BigDecimal("0.30"),       // holdbackRate (non-zero → L92)
                LocalDate.of(2026, 2, 1), false, null);

        assertThat(s.getRefundedAmount()).isEqualByComparingTo("500");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(s.getCreatedAt()).isEqualTo(created);
        assertThat(s.getUpdatedAt()).isEqualTo(updated);
        assertThat(s.getHoldbackAmount()).isEqualByComparingTo("2760");
        assertThat(s.getHoldbackRate()).isEqualByComparingTo("0.30");
    }

    // ===== Settlement.validatePaymentId 경계 (L155, paymentId <= 0) =====

    @Test
    @DisplayName("[gap] paymentId 경계값 0 은 거부된다 (<=0 경계)")
    void createFromPaymentRejectsZeroPaymentId() {
        assertThatThrownBy(() -> Settlement.createFromPayment(
                0L, 10L, new BigDecimal("10000"), LocalDate.now()))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    // ===== Settlement.applyHoldback 경계·플래그 (L406 rate 0~1 경계, L416 released 분기) =====

    @Test
    @DisplayName("[gap] 보류율 경계값 0 은 유효하며 즉시 released + releasedAt 세팅 (L406 signum<0 경계, L416 분기)")
    void applyHoldbackRateZeroIsValidAndImmediatelyReleased() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("10000"), LocalDate.now());

        s.applyHoldback(BigDecimal.ZERO, LocalDate.now().plusDays(30));

        assertThat(s.isHoldbackReleased()).isTrue();
        assertThat(s.getHoldbackReleasedAt()).isNotNull();
    }

    @Test
    @DisplayName("[gap] 보류율 경계값 1 은 유효하며 released 되지 않는다 (L406 compareTo ONE >0 경계, L416 분기)")
    void applyHoldbackRateOneIsValidAndNotReleased() {
        Settlement s = Settlement.createFromPayment(2L, 11L, new BigDecimal("10000"), LocalDate.now());

        s.applyHoldback(BigDecimal.ONE, LocalDate.now().plusDays(30));

        assertThat(s.getHoldbackRate()).isEqualByComparingTo("1");
        assertThat(s.isHoldbackReleased()).isFalse();
        assertThat(s.getHoldbackReleasedAt()).isNull();
    }

    @Test
    @DisplayName("[gap] 보류율 범위 밖(1 초과 / 음수)은 거부된다")
    void applyHoldbackRejectsOutOfRangeRate() {
        Settlement s = Settlement.createFromPayment(3L, 12L, new BigDecimal("10000"), LocalDate.now());

        assertThatThrownBy(() -> s.applyHoldback(new BigDecimal("1.01"), LocalDate.now()))
                .isInstanceOf(SettlementInvariantViolationException.class);
        assertThatThrownBy(() -> s.applyHoldback(new BigDecimal("-0.01"), LocalDate.now()))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    // ===== Settlement.consumeHoldbackForRefund 경계 (L444 refund<=0, L447 holdback<=0) =====

    @Test
    @DisplayName("[gap] 환불금 경계값 0 은 거부된다 (L444 signum<=0 경계)")
    void consumeHoldbackRejectsZeroRefund() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("10000"), LocalDate.now());
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.now().plusDays(30));

        assertThatThrownBy(() -> s.consumeHoldbackForRefund(BigDecimal.ZERO))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    @Test
    @DisplayName("[gap] holdback 잔액 경계값 0 이면 상태 변화 없이 0 반환 (L447 signum<=0 경계)")
    void consumeHoldbackWithZeroBalanceReturnsZeroWithoutStateChange() {
        Settlement s = Settlement.rehydrate(
                1L, 10L, 20L, new BigDecimal("10000"), BigDecimal.ZERO,
                new BigDecimal("300"), new BigDecimal("0.03"), new BigDecimal("9700"),
                SettlementStatus.REQUESTED, LocalDate.now(), null, null,
                LocalDateTime.now(), LocalDateTime.now(), 1L,
                BigDecimal.ZERO,                    // holdbackAmount = 0
                new BigDecimal("0.30"),
                LocalDate.now().plusDays(30),
                false,                              // holdbackReleased = false
                null);

        BigDecimal consumed = s.consumeHoldbackForRefund(new BigDecimal("100"));

        assertThat(consumed).isEqualByComparingTo("0");
        // 원본: 잔액 0 이면 즉시 반환 → released/releasedAt 불변. 뮤턴트(<0)는 진입해 released 를 true 로 바꾼다.
        assertThat(s.isHoldbackReleased()).isFalse();
        assertThat(s.getHoldbackReleasedAt()).isNull();
    }

    // ===== Settlement.isHoldbackReleasable 경계 (L471 holdbackAmount.signum() > 0) =====

    @Test
    @DisplayName("[gap] holdback 잔액 0 이면 release 예정일이 지났어도 releasable 아니다 (L471 >0 경계)")
    void isHoldbackReleasableFalseWhenBalanceZero() {
        Settlement s = Settlement.rehydrate(
                1L, 10L, 20L, new BigDecimal("10000"), BigDecimal.ZERO,
                new BigDecimal("300"), new BigDecimal("0.03"), new BigDecimal("9700"),
                SettlementStatus.REQUESTED, LocalDate.now(), null, null,
                LocalDateTime.now(), LocalDateTime.now(), 1L,
                BigDecimal.ZERO,                    // holdbackAmount = 0
                new BigDecimal("0.30"),
                LocalDate.now().minusDays(1),       // releaseDate 이미 지남
                false, null);                       // 아직 released 아님

        assertThat(s.isHoldbackReleasable(LocalDate.now())).isFalse();
    }

    // ===== SettlementAdjustment 생성자 (L40 amount>=0 경계, L49 status 폴백) =====

    @Test
    @DisplayName("[gap] 조정 금액 경계값 0 은 거부된다 (L40 signum>=0 경계)")
    void adjustmentRejectsZeroAmount() {
        assertThatThrownBy(() -> SettlementAdjustment.rehydrate(
                1L, 10L, 5L, null, null,
                BigDecimal.ZERO, SettlementAdjustmentStatus.CONFIRMED, LocalDate.now(), LocalDateTime.now()))
                .isInstanceOf(NegativeAdjustmentAmountException.class);
    }

    @Test
    @DisplayName("[gap] rehydrate 는 non-default status(CONFIRMED)를 PENDING 으로 덮지 않는다 (L49 폴백)")
    void adjustmentRehydratePreservesNonDefaultStatus() {
        SettlementAdjustment a = SettlementAdjustment.rehydrate(
                1L, 10L, 5L, null, null,
                new BigDecimal("-100"), SettlementAdjustmentStatus.CONFIRMED, LocalDate.now(), LocalDateTime.now());

        assertThat(a.getStatus()).isEqualTo(SettlementAdjustmentStatus.CONFIRMED);
    }

    // ===== SettlementAdjustment.ofReconciliation 경계 (L92 clawback <= 0) =====

    @Test
    @DisplayName("[gap] 대사 clawback 경계값 0 은 정확히 InvariantViolation 으로 거부된다 (L92 <=0 경계, 예외 타입 구분)")
    void ofReconciliationRejectsZeroClawbackWithSpecificException() {
        // 뮤턴트(<0)는 0 을 통과시켜 negate(0)=0 으로 생성자에 도달 → NegativeAdjustmentAmountException(하위 타입)을 던진다.
        // 원본은 팩토리 가드에서 SettlementInvariantViolationException(정확히 그 타입)을 던진다.
        assertThatThrownBy(() -> SettlementAdjustment.ofReconciliation(
                10L, 5L, BigDecimal.ZERO, LocalDate.now()))
                .isExactlyInstanceOf(SettlementInvariantViolationException.class)
                .hasMessageContaining("clawback");
    }

    // ===== BusinessDayCalculator.extraHolidays (L218 EmptyObjectReturnVals) =====

    @Test
    @DisplayName("[gap] 주입된 추가 공휴일은 빈 세트가 아니라 그대로 반환된다 (L218 반환값 뮤턴트)")
    void extraHolidaysReturnsInjectedSet() {
        LocalDate temp = LocalDate.of(2026, 7, 20);
        BusinessDayCalculator cal = new BusinessDayCalculator(Set.of(temp));

        assertThat(cal.extraHolidays()).containsExactly(temp);
    }
}
