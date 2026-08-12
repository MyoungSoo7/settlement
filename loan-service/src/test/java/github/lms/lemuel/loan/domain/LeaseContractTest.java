package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리스·할부 계약 애그리거트 규격 — 상태 전이 가드와 중도해지 정산.
 */
class LeaseContractTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 13, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final BigDecimal PENALTY_RATE = new BigDecimal("3");

    private static LeaseSchedule schedule() {
        return LeaseSchedule.of(AssetFinanceType.FINANCE_LEASE, new BigDecimal("30000000"),
                BigDecimal.ZERO, new BigDecimal("3000000"), new BigDecimal("6000000"), 36, new BigDecimal("6.0"));
    }

    private static LeaseContract activeContract() {
        LeaseContract contract = LeaseContract.apply(
                Borrower.corporate(1L, "㈜테스트", "1234567890"), "지게차 3톤", schedule(), NOW);
        contract.approve();
        contract.activate(NOW);
        return contract;
    }

    @Nested
    @DisplayName("생명주기")
    class Lifecycle {

        @Test
        @DisplayName("신청 → 승인 → 인도(개시) 순으로만 개시된다")
        void activatesOnlyAfterApproval() {
            LeaseContract contract = LeaseContract.apply(
                    Borrower.individual(1L, "홍길동"), "승용차 1대", schedule(), NOW);

            assertThat(contract.getStatus()).isEqualTo(LeaseStatus.APPLIED);
            assertThatThrownBy(() -> contract.activate(NOW))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("허용되지 않는 상태 전이");

            contract.approve();
            contract.activate(NOW);

            assertThat(contract.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
            assertThat(contract.getActivatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("연체는 납입으로 정상화된다 — 회차 상품의 정상 흐름")
        void overdueReturnsToActiveOnPayment() {
            LeaseContract contract = activeContract();
            contract.markOverdue();

            contract.payInstallment();

            assertThat(contract.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
            assertThat(contract.getPaidInstallments()).isEqualTo(1);
        }

        @Test
        @DisplayName("기한이익상실은 연체를 거쳐야 도달한다 — 개시 상태에서 직행 금지")
        void defaultRequiresOverdueFirst() {
            LeaseContract contract = activeContract();

            assertThatThrownBy(contract::markDefaulted)
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("허용되지 않는 상태 전이");

            contract.markOverdue();
            contract.markDefaulted();

            assertThat(contract.getStatus()).isEqualTo(LeaseStatus.DEFAULTED);
        }

        @Test
        @DisplayName("계약 기간을 넘는 회차는 수납할 수 없다")
        void cannotPayBeyondTerm() {
            LeaseContract contract = activeContract();
            for (int i = 0; i < 36; i++) {
                contract.payInstallment();
            }

            assertThatThrownBy(contract::payInstallment)
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("계약 기간을 넘는 회차");
        }

        @Test
        @DisplayName("전 회차 수납 전에는 만기 종료할 수 없다")
        void maturesOnlyAfterAllInstallments() {
            LeaseContract contract = activeContract();
            contract.payInstallment();

            assertThatThrownBy(() -> contract.mature(NOW))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("전 회차 수납 전");

            for (int i = 1; i < 36; i++) {
                contract.payInstallment();
            }
            contract.mature(NOW);

            assertThat(contract.getStatus()).isEqualTo(LeaseStatus.MATURED);
            assertThat(contract.outstandingBalance())
                    .as("만기 잔액은 잔존가치")
                    .isEqualByComparingTo("6000000");
        }
    }

    @Nested
    @DisplayName("무산 경로·복원")
    class RejectionAndRestore {

        @Test
        @DisplayName("심사 거절과 인도 전 취소는 각각 제 자리에서만 가능하다")
        void rejectAndCancelHaveTheirOwnPlaces() {
            LeaseContract applied = LeaseContract.apply(
                    Borrower.individual(1L, "홍길동"), "노트북 10대", schedule(), NOW);
            assertThatThrownBy(applied::cancel)
                    .isInstanceOf(LoanInvariantViolationException.class);
            applied.reject();
            assertThat(applied.getStatus()).isEqualTo(LeaseStatus.REJECTED);

            LeaseContract approved = LeaseContract.apply(
                    Borrower.individual(2L, "김영희"), "서버 랙", schedule(), NOW);
            approved.approve();
            assertThatThrownBy(approved::reject)
                    .isInstanceOf(LoanInvariantViolationException.class);
            approved.cancel();
            assertThat(approved.getStatus()).isEqualTo(LeaseStatus.CANCELLED);
        }

        @Test
        @DisplayName("신청 필수값이 비면 계약이 만들어지지 않는다")
        void rejectsIncompleteApplication() {
            LeaseSchedule schedule = schedule();
            assertThatThrownBy(() -> LeaseContract.apply(null, "지게차", schedule, NOW))
                    .isInstanceOf(LoanInvariantViolationException.class).hasMessageContaining("차주");
            assertThatThrownBy(() -> LeaseContract.apply(
                    Borrower.individual(1L, "홍길동"), " ", schedule, NOW))
                    .isInstanceOf(LoanInvariantViolationException.class).hasMessageContaining("물건 표시");
            assertThatThrownBy(() -> LeaseContract.apply(
                    Borrower.individual(1L, "홍길동"), "지게차", null, NOW))
                    .isInstanceOf(LoanInvariantViolationException.class).hasMessageContaining("스케줄");
            assertThatThrownBy(() -> LeaseContract.apply(
                    Borrower.individual(1L, "홍길동"), "지게차", schedule, null))
                    .isInstanceOf(LoanInvariantViolationException.class).hasMessageContaining("신청 시각");
        }

        @Test
        @DisplayName("영속 복원은 상태·납입 회차를 그대로 되살린다")
        void reconstituteRestoresState() {
            LeaseSchedule schedule = schedule();

            LeaseContract restored = LeaseContract.reconstitute(42L,
                    Borrower.corporate(7L, "㈜복원", "9998887776"), AssetFinanceType.FINANCE_LEASE,
                    "굴착기", schedule, LeaseStatus.ACTIVE, 5, NOW, NOW, null);

            assertThat(restored.getId()).isEqualTo(42L);
            assertThat(restored.getType()).isEqualTo(AssetFinanceType.FINANCE_LEASE);
            assertThat(restored.getAssetDescription()).isEqualTo("굴착기");
            assertThat(restored.getPaidInstallments()).isEqualTo(5);
            assertThat(restored.getAppliedAt()).isEqualTo(NOW);
            assertThat(restored.getClosedAt()).isNull();
            assertThat(restored.getBorrower().name()).isEqualTo("㈜복원");
            assertThat(restored.getSchedule()).isEqualTo(schedule);
            assertThat(restored.outstandingBalance())
                    .isEqualByComparingTo(schedule.balanceAfter(5));
        }

        @Test
        @DisplayName("개시 전에는 회차 수납·정산 조회를 막는다")
        void billingRequiresActiveContract() {
            LeaseContract contract = LeaseContract.apply(
                    Borrower.individual(1L, "홍길동"), "프린터", schedule(), NOW);

            assertThatThrownBy(contract::payInstallment)
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("계약이 유효할 때만");
            assertThatThrownBy(() -> contract.quoteEarlyTermination(PENALTY_RATE))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("계약이 유효할 때만");
        }
    }

    @Nested
    @DisplayName("중도해지 정산")
    class EarlyTermination {

        @Test
        @DisplayName("잔액에 규정손해금을 더하고 보증금을 상계해 청구한다")
        void settlesWithPenaltyAndDepositOffset() {
            LeaseContract contract = activeContract();
            for (int i = 0; i < 12; i++) {
                contract.payInstallment();
            }
            BigDecimal balance = contract.outstandingBalance();

            EarlyTerminationQuote quote = contract.terminateEarly(PENALTY_RATE, NOW);

            assertThat(contract.getStatus()).isEqualTo(LeaseStatus.EARLY_TERMINATED);
            assertThat(quote.settledInstallmentNo()).isEqualTo(12);
            assertThat(quote.outstandingBalance()).isEqualByComparingTo(balance);
            assertThat(quote.penalty())
                    .isEqualByComparingTo(balance.multiply(PENALTY_RATE).divide(new BigDecimal("100")).setScale(0, java.math.RoundingMode.HALF_UP));
            assertThat(quote.depositOffset()).isEqualByComparingTo("3000000");
            assertThat(quote.payable())
                    .isEqualByComparingTo(balance.add(quote.penalty()).subtract(new BigDecimal("3000000")));
            assertThat(quote.refundDue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("잔액에 잔존가치가 이미 포함되어 있다 — 따로 더하면 이중 청구다")
        void balanceAlreadyIncludesResidualValue() {
            LeaseContract contract = activeContract();
            for (int i = 0; i < 35; i++) {
                contract.payInstallment();
            }

            EarlyTerminationQuote quote = contract.quoteEarlyTermination(BigDecimal.ZERO);

            assertThat(quote.outstandingBalance())
                    .as("마지막 회차 직전 잔액은 잔존가치보다 크고, 만기 잔액(잔존가치)에 수렴한다")
                    .isGreaterThan(new BigDecimal("6000000"));
            assertThat(contract.getSchedule().balanceAfter(36)).isEqualByComparingTo("6000000");
        }

        @Test
        @DisplayName("보증금이 청구액보다 크면 청구는 0 이고 차액은 반환 채무가 된다")
        void refundsWhenDepositExceedsPayable() {
            LeaseSchedule bigDeposit = LeaseSchedule.of(AssetFinanceType.FINANCE_LEASE,
                    new BigDecimal("12000000"), BigDecimal.ZERO, new BigDecimal("5000000"),
                    BigDecimal.ZERO, 12, BigDecimal.ZERO);
            LeaseContract contract = LeaseContract.apply(
                    Borrower.individual(1L, "홍길동"), "복합기", bigDeposit, NOW);
            contract.approve();
            contract.activate(NOW);
            for (int i = 0; i < 11; i++) {
                contract.payInstallment();
            }

            EarlyTerminationQuote quote = contract.terminateEarly(BigDecimal.ZERO, NOW);

            // 잔액 = 7,000,000/12 × 1회차분 ≈ 583,334 < 보증금 5,000,000
            assertThat(quote.payable()).isEqualByComparingTo("0");
            assertThat(quote.refundDue()).isPositive();
        }

        @Test
        @DisplayName("종료된 계약은 중도해지할 수 없다")
        void cannotTerminateClosedContract() {
            LeaseContract contract = activeContract();
            for (int i = 0; i < 36; i++) {
                contract.payInstallment();
            }
            contract.mature(NOW);

            assertThatThrownBy(() -> contract.terminateEarly(PENALTY_RATE, NOW))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("이미 종료된 계약");
        }

        @Test
        @DisplayName("규정손해금률 상한을 넘으면 거부한다")
        void rejectsExcessivePenaltyRate() {
            LeaseContract contract = activeContract();

            assertThatThrownBy(() -> contract.quoteEarlyTermination(new BigDecimal("10.01")))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("규정손해금률");
        }

        @Test
        @DisplayName("만기 회차는 중도해지 대상이 아니다 — 만기 종료로 가야 한다")
        void lastInstallmentIsNotEarlyTermination() {
            LeaseContract contract = activeContract();
            for (int i = 0; i < 36; i++) {
                contract.payInstallment();
            }

            assertThatThrownBy(() -> contract.quoteEarlyTermination(PENALTY_RATE))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("만기 회차는 만기 종료");
        }
    }
}
