package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.Gender;
import github.lms.lemuel.insurance.domain.ProposalQuote;
import github.lms.lemuel.insurance.domain.SalesChannel;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 가입설계 산출 유스케이스 — 요율 조회 + 보험료 산출 + 스냅샷 저장.
 *
 * <p>생년월일은 보험나이 산정에만 쓰고 저장하지 않는다(PII 최소화).
 */
public interface CreateProposalUseCase {

    ProposalSummary create(CreateProposalCommand cmd);

    record CreateProposalCommand(String consultationId, String productCode, String fcId,
                                 String insuredName, LocalDate insuredBirthDate, Gender insuredGender,
                                 BigDecimal coverageAmount, int paymentTermYears,
                                 SalesChannel salesChannel, String partnerBankCode) {
    }

    /** 설계 조회 응답 공용 뷰 — 산출 스냅샷 전체(요율 근거 포함)를 노출한다. */
    record ProposalSummary(String proposalId, String productCode, String insuredName,
                           int insuranceAge, BigDecimal coverageAmount, int paymentTermYears,
                           BigDecimal appliedRatePerMille, BigDecimal annualPremium,
                           String status, LocalDate quotedOn, LocalDate validUntil,
                           String convertedApplicationId) {

        public static ProposalSummary from(ProposalQuote p) {
            return new ProposalSummary(
                    p.getProposalId(), p.getProductCode(), p.getInsuredName(),
                    p.getInsuranceAge(), p.getCoverageAmount(), p.getPaymentTermYears(),
                    p.getAppliedRatePerMille(), p.getAnnualPremium(),
                    p.getStatus().name(), p.getQuotedOn(), p.getValidUntil(),
                    p.getConvertedApplicationId());
        }
    }
}
