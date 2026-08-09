package github.lms.lemuel.account.banking.pension.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 제도·수급형태 상수 규칙 테스트.
 *
 * <p>애그리게이트가 제도별 if 분기를 갖지 않는 대신 이 상수들이 규칙의 정본이므로, 여기서
 * 세 제도 × 두 납입주체의 조합표 전체를 못 박아 둔다.
 */
class PensionSchemeTest {

    @Test
    void 퇴직연금_제도는_DB_DC_IRP_세_가지뿐이다() {
        assertThat(PensionScheme.values())
                .containsExactly(PensionScheme.DB, PensionScheme.DC, PensionScheme.IRP);
    }

    @Test
    void 사업장명은_DB와_DC만_필수다() {
        assertThat(PensionScheme.DB.requiresEmployerName()).isTrue();
        assertThat(PensionScheme.DC.requiresEmployerName()).isTrue();
        assertThat(PensionScheme.IRP.requiresEmployerName()).isFalse();
    }

    @Test
    void 중도인출은_DB만_제도적으로_불가하다() {
        assertThat(PensionScheme.DB.permitsMidWithdrawal()).isFalse();
        assertThat(PensionScheme.DC.permitsMidWithdrawal()).isTrue();
        assertThat(PensionScheme.IRP.permitsMidWithdrawal()).isTrue();
    }

    @Test
    void 납입주체_허용_조합표는_제도가_정한다() {
        assertThat(PensionScheme.DB.permitsContributionFrom(ContributionSource.EMPLOYER)).isTrue();
        assertThat(PensionScheme.DB.permitsContributionFrom(ContributionSource.EMPLOYEE)).isFalse();
        assertThat(PensionScheme.DC.permitsContributionFrom(ContributionSource.EMPLOYER)).isTrue();
        assertThat(PensionScheme.DC.permitsContributionFrom(ContributionSource.EMPLOYEE)).isTrue();
        assertThat(PensionScheme.IRP.permitsContributionFrom(ContributionSource.EMPLOYER)).isFalse();
        assertThat(PensionScheme.IRP.permitsContributionFrom(ContributionSource.EMPLOYEE)).isTrue();
    }

    @Test
    void 허용_납입주체_집합은_밖에서_변경할_수_없다() {
        assertThat(PensionScheme.DC.allowedContributionSources())
                .containsExactlyInAnyOrder(ContributionSource.EMPLOYER, ContributionSource.EMPLOYEE);
        assertThatThrownBy(() -> PensionScheme.DB.allowedContributionSources().add(ContributionSource.EMPLOYEE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 연금수급은_만55세와_가입기간10년을_모두_요구한다() {
        assertThat(BenefitType.ANNUITY.minimumAge()).isEqualTo(55);
        assertThat(BenefitType.ANNUITY.minimumSubscribedYears()).isEqualTo(10);
        assertThat(BenefitType.ANNUITY.isEligible(55, 10)).isTrue();
        assertThat(BenefitType.ANNUITY.isEligible(54, 10)).isFalse();
        assertThat(BenefitType.ANNUITY.isEligible(55, 9)).isFalse();
    }

    @Test
    void 일시금수급은_가입기간_요건이_없다() {
        assertThat(BenefitType.LUMP_SUM.minimumAge()).isEqualTo(55);
        assertThat(BenefitType.LUMP_SUM.minimumSubscribedYears()).isZero();
        assertThat(BenefitType.LUMP_SUM.isEligible(55, 0)).isTrue();
        assertThat(BenefitType.LUMP_SUM.isEligible(54, 30)).isFalse();
    }

    @Test
    void 계약_상태와_거래_종류는_정해진_값만_갖는다() {
        assertThat(PensionStatus.values()).containsExactly(
                PensionStatus.ACCUMULATING, PensionStatus.RECEIVING, PensionStatus.CLOSED);
        assertThat(PensionTransactionType.values()).containsExactly(
                PensionTransactionType.CONTRIBUTION, PensionTransactionType.INTEREST,
                PensionTransactionType.BENEFIT, PensionTransactionType.MID_WITHDRAWAL);
        assertThat(ContributionSource.values())
                .containsExactly(ContributionSource.EMPLOYER, ContributionSource.EMPLOYEE);
    }

    @Test
    void 법정_중도인출_사유는_6종이다() {
        assertThat(MidWithdrawalReason.values()).containsExactly(
                MidWithdrawalReason.HOMELESS_HOUSE_PURCHASE,
                MidWithdrawalReason.LONG_TERM_CARE_6_MONTHS,
                MidWithdrawalReason.BANKRUPTCY,
                MidWithdrawalReason.PERSONAL_REHABILITATION,
                MidWithdrawalReason.NATURAL_DISASTER,
                MidWithdrawalReason.MINISTER_NOTICE);
    }
}
