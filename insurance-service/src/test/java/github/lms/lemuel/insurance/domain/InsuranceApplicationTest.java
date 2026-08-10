package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidApplicationException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationTransitionException;
import github.lms.lemuel.insurance.domain.exception.InvalidSalesChannelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 청약(InsuranceApplication) 언더라이팅 상태머신 테스트.
 *
 * <p>허용 전이 3개: SUBMITTED→UNDER_REVIEW, UNDER_REVIEW→APPROVED, UNDER_REVIEW→REJECTED.
 * 그 외 모든 전이는 {@link InvalidApplicationTransitionException} — 조용한 무시 금지.
 */
@DisplayName("InsuranceApplication 언더라이팅 상태머신")
class InsuranceApplicationTest {

    private static InsuranceApplication submitted() {
        return InsuranceApplication.submit(
                null, "PROD-1", "fc-100", "김피보", "홍길동",
                new BigDecimal("100000000.00"), new BigDecimal("1200000.00"),
                SalesChannel.FC, null);
    }

    @Nested
    @DisplayName("submit — 청약 접수")
    class Submit {

        @Test
        @DisplayName("접수 시 applicationId 자동 채번 + SUBMITTED 로 시작한다")
        void submitsWithGeneratedIdAndSubmittedStatus() {
            InsuranceApplication app = submitted();

            assertThat(app.getApplicationId()).isNotBlank();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
            assertThat(app.getRejectReason()).isNull();
            assertThat(app.getSalesChannel()).isEqualTo(SalesChannel.FC);
        }

        @Test
        @DisplayName("보장금액·보험료가 0 이하이면 거부한다")
        void rejectsNonPositiveAmounts() {
            assertThatThrownBy(() -> InsuranceApplication.submit(
                    null, "PROD-1", "fc-100", "김피보", "홍길동",
                    BigDecimal.ZERO, new BigDecimal("1200000.00"), SalesChannel.FC, null))
                    .isInstanceOf(InvalidApplicationException.class);

            assertThatThrownBy(() -> InsuranceApplication.submit(
                    null, "PROD-1", "fc-100", "김피보", "홍길동",
                    new BigDecimal("100000000.00"), new BigDecimal("-1"), SalesChannel.FC, null))
                    .isInstanceOf(InvalidApplicationException.class);
        }

        @Test
        @DisplayName("BANCA 청약은 판매 은행 필수 — Policy 와 동일 불변식")
        void enforcesBancaBankInvariant() {
            assertThatThrownBy(() -> InsuranceApplication.submit(
                    null, "PROD-1", "teller-1", "김피보", "홍길동",
                    new BigDecimal("100000000.00"), new BigDecimal("1200000.00"),
                    SalesChannel.BANCA, null))
                    .isInstanceOf(InvalidSalesChannelException.class);

            InsuranceApplication banca = InsuranceApplication.submit(
                    null, "PROD-1", "teller-1", "김피보", "홍길동",
                    new BigDecimal("100000000.00"), new BigDecimal("1200000.00"),
                    SalesChannel.BANCA, "BANK-KB");
            assertThat(banca.getPartnerBankCode()).isEqualTo("BANK-KB");
        }
    }

    @Nested
    @DisplayName("전이 — startReview / approve / reject")
    class Transitions {

        @Test
        @DisplayName("SUBMITTED → UNDER_REVIEW → APPROVED 정상 경로")
        void approvesThroughReview() {
            InsuranceApplication app = submitted();

            app.startReview();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.UNDER_REVIEW);

            app.approve();
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
            assertThat(app.getStatus().isTerminal()).isTrue();
        }

        @Test
        @DisplayName("UNDER_REVIEW → REJECTED — 반려 사유가 기록된다")
        void rejectsWithReason() {
            InsuranceApplication app = submitted();
            app.startReview();

            app.reject("고지의무 위반 이력");

            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(app.getRejectReason()).isEqualTo("고지의무 위반 이력");
        }

        @Test
        @DisplayName("심사 없이 바로 승인할 수 없다 — SUBMITTED → APPROVED 차단")
        void rejectsApprovalWithoutReview() {
            assertThatThrownBy(() -> submitted().approve())
                    .isInstanceOf(InvalidApplicationTransitionException.class);
        }

        @Test
        @DisplayName("이미 승인된 청약은 재심사·재승인·반려할 수 없다 (terminal)")
        void terminalStateBlocksAllTransitions() {
            InsuranceApplication app = submitted();
            app.startReview();
            app.approve();

            assertThatThrownBy(app::startReview)
                    .isInstanceOf(InvalidApplicationTransitionException.class);
            assertThatThrownBy(app::approve)
                    .isInstanceOf(InvalidApplicationTransitionException.class);
            assertThatThrownBy(() -> app.reject("사유"))
                    .isInstanceOf(InvalidApplicationTransitionException.class);
        }

        @Test
        @DisplayName("반려 사유 없는 반려는 거부한다")
        void rejectsBlankRejectReason() {
            InsuranceApplication app = submitted();
            app.startReview();

            assertThatThrownBy(() -> app.reject("  "))
                    .isInstanceOf(InvalidApplicationException.class);
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.UNDER_REVIEW);
        }
    }

    @Nested
    @DisplayName("전이표 — ApplicationStatus")
    class TransitionTable {

        @Test
        @DisplayName("허용 전이는 정확히 3개, terminal 은 APPROVED·REJECTED")
        void tableMatchesSpec() {
            assertThat(ApplicationStatus.SUBMITTED.canTransitionTo(ApplicationStatus.UNDER_REVIEW)).isTrue();
            assertThat(ApplicationStatus.UNDER_REVIEW.canTransitionTo(ApplicationStatus.APPROVED)).isTrue();
            assertThat(ApplicationStatus.UNDER_REVIEW.canTransitionTo(ApplicationStatus.REJECTED)).isTrue();

            assertThat(ApplicationStatus.SUBMITTED.canTransitionTo(ApplicationStatus.APPROVED)).isFalse();
            assertThat(ApplicationStatus.SUBMITTED.canTransitionTo(ApplicationStatus.REJECTED)).isFalse();
            for (ApplicationStatus terminal : new ApplicationStatus[]{
                    ApplicationStatus.APPROVED, ApplicationStatus.REJECTED}) {
                assertThat(terminal.isTerminal()).isTrue();
                for (ApplicationStatus target : ApplicationStatus.values()) {
                    assertThat(terminal.canTransitionTo(target)).isFalse();
                }
            }
        }
    }
}
