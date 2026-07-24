package github.lms.lemuel.account.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cut-over 잔존 정산예정금 청산 계획(ADR 0026 Option A 백필) — 셀러별 SETTLEMENT_SCHEDULED 순차변만 청산.
 */
class ScheduledResidualClearingTest {

    /** Option A 이전 규칙의 역사적 전표를 reconstitute 로 만든다(생성=DR SCHEDULED, 확정=CR SCHEDULED). */
    private static AccountEntry legacyCreated(String sellerId, String settlementId, String amount) {
        return AccountEntry.reconstitute(null, OwnerType.SELLER, sellerId,
                GlAccount.SETTLEMENT_SCHEDULED, GlAccount.SELLER_PAYABLE, new BigDecimal(amount),
                "SETTLEMENT_CREATED", settlementId, "lemuel.settlement.created",
                java.time.LocalDateTime.now());
    }

    private static AccountEntry legacyConfirmed(String sellerId, String settlementId, String amount) {
        return AccountEntry.reconstitute(null, OwnerType.SELLER, sellerId,
                GlAccount.SELLER_PAYABLE, GlAccount.SETTLEMENT_SCHEDULED, new BigDecimal(amount),
                "SETTLEMENT_CONFIRMED", settlementId, "lemuel.settlement.confirmed",
                java.time.LocalDateTime.now());
    }

    @Test
    @DisplayName("생성만 있고 확정 없는 셀러 → 순차변 잔액 전액 청산분개(DR CASH / CR SCHEDULED)")
    void createdWithoutConfirmed_isCleared() {
        List<AccountEntry> plan = ScheduledResidualClearing.plan(List.of(
                legacyCreated("777", "S1", "43425")));

        assertThat(plan).hasSize(1);
        AccountEntry c = plan.get(0);
        assertThat(c.getOwnerId()).isEqualTo("777");
        assertThat(c.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(c.getCreditAccount()).isEqualTo(GlAccount.SETTLEMENT_SCHEDULED);
        assertThat(c.getAmount()).isEqualByComparingTo("43425");
        assertThat(c.getRefType()).isEqualTo("SETTLEMENT_SCHED_CLEARING");
    }

    @Test
    @DisplayName("생성=확정(완전 상계)된 셀러 → 잔액 0, 청산 대상 아님")
    void fullyConfirmed_isNotCleared() {
        List<AccountEntry> plan = ScheduledResidualClearing.plan(List.of(
                legacyCreated("777", "S1", "43425"),
                legacyConfirmed("777", "S1", "43425")));

        assertThat(plan).isEmpty();
    }

    @Test
    @DisplayName("부분 확정 → 잔여 순차변만 청산")
    void partiallyConfirmed_clearsRemainder() {
        List<AccountEntry> plan = ScheduledResidualClearing.plan(List.of(
                legacyCreated("777", "S1", "43425"),
                legacyCreated("777", "S2", "10000"),
                legacyConfirmed("777", "S1", "43425")));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).getAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("이미 청산분개가 있으면(잔액 0) 다시 계획되지 않는다 — 멱등, 반복 실행 불변")
    void alreadyCleared_isIdempotent() {
        List<AccountEntry> firstRun = ScheduledResidualClearing.plan(List.of(
                legacyCreated("777", "S1", "43425")));
        // 첫 실행 청산분개(SETTLEMENT_SCHED_CLEARING, CR SCHEDULED)를 원장에 포함해 재계산
        List<AccountEntry> withClearing = List.of(
                legacyCreated("777", "S1", "43425"),
                firstRun.get(0));

        List<AccountEntry> secondRun = ScheduledResidualClearing.plan(withClearing);

        assertThat(secondRun).isEmpty();
    }

    @Test
    @DisplayName("CORPORATE·비예정 계정 전표는 무시한다")
    void ignoresNonSellerAndNonScheduled() {
        List<AccountEntry> plan = ScheduledResidualClearing.plan(List.of(
                AccountEntry.corporateLoanDisbursed("005930", "CL1", new BigDecimal("1000000")),
                AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000"))));

        assertThat(plan).isEmpty();
    }

    @Test
    @DisplayName("셀러가 여럿이면 셀러별로 청산분개를 만든다")
    void multipleSellers_clearedIndependently() {
        List<AccountEntry> plan = ScheduledResidualClearing.plan(List.of(
                legacyCreated("777", "S1", "43425"),
                legacyCreated("888", "S2", "10000")));

        assertThat(plan).hasSize(2);
        assertThat(plan).extracting(AccountEntry::getOwnerId).containsExactlyInAnyOrder("777", "888");
    }
}
