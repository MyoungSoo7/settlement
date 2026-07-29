package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.SecuredLoan;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 담보/개인신용 대출 응답.
 *
 * <p>차주는 식별자와 유형만 노출하고 이름·사업자번호는 싣지 않는다 — 조회 응답이 PII 유출 경로가 되지
 * 않게 하려는 것으로, 소유자 본인은 이미 자기 이름을 알고 운영자는 별도 감사 경로를 쓴다.
 */
public record SecuredLoanResponse(
        Long loanId,
        String productType,
        Long borrowerUserId,
        String borrowerType,
        BigDecimal principal,
        BigDecimal outstanding,
        int termMonths,
        BigDecimal annualRatePercent,
        String repaymentMethod,
        Integer creditScore,
        String creditGrade,
        String status,
        CollateralView collateral,
        LocalDateTime createdAt) {

    /** 담보 요약 — 무담보 상품이면 {@code null}. */
    public record CollateralView(Long collateralId, String type, String description,
                                 BigDecimal appraisedValue, String status) {

        static CollateralView from(Collateral collateral) {
            return new CollateralView(collateral.getId(), collateral.getType().name(),
                    collateral.getDescription(), collateral.getAppraisedValue(),
                    collateral.getStatus().name());
        }
    }

    public static SecuredLoanResponse from(SecuredLoan loan) {
        return new SecuredLoanResponse(
                loan.getId(),
                loan.getProductType().name(),
                loan.getBorrower().userId(),
                loan.getBorrower().type().name(),
                loan.getPrincipal(),
                loan.getOutstanding(),
                loan.getTermMonths(),
                loan.getAnnualRatePercent(),
                loan.getRepaymentMethod().name(),
                loan.getCreditScore(),
                loan.getCreditGrade(),
                loan.getStatus().name(),
                loan.getCollateral() == null ? null : CollateralView.from(loan.getCollateral()),
                loan.getCreatedAt());
    }
}
