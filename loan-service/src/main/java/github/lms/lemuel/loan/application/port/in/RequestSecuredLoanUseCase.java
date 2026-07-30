package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CollateralType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;

import java.math.BigDecimal;

/**
 * 담보/개인신용 대출 신청 인바운드 포트. 신청 시점에 심사(한도·금리 산정)까지 수행한다.
 *
 * <p>{@code borrowerUserId} 는 <b>요청 파라미터가 아니라 JWT 주체에서 파생</b>되어야 한다(IDOR 방지).
 * 웹 어댑터가 인증 주체를 커맨드에 담아 넘긴다.
 */
public interface RequestSecuredLoanUseCase {

    /**
     * 주택담보대출 신청 커맨드.
     *
     * @param registrationNo          사업자등록번호 — 있으면 법인 차주, 없으면 개인 차주로 해석한다
     * @param declaredCollateralValue 신청자가 제시한 담보 평가액(실거래가 조회 불가 시 폴백)
     * @param dealRecordKey           실거래가 조회 키(commondata recordKey 접두어) — 없으면 제시값 확정
     */
    record MortgageCommand(Long borrowerUserId, String borrowerName, String registrationNo,
                           String collateralDescription, BigDecimal declaredCollateralValue,
                           BigDecimal principal, int termMonths, RepaymentMethod repaymentMethod,
                           String dealRecordKey) {

        /** 실거래가 조회 키 없는 신청(기존 호출부 호환) — 제시값이 그대로 확정된다. */
        public MortgageCommand(Long borrowerUserId, String borrowerName, String registrationNo,
                               String collateralDescription, BigDecimal declaredCollateralValue,
                               BigDecimal principal, int termMonths, RepaymentMethod repaymentMethod) {
            this(borrowerUserId, borrowerName, registrationNo, collateralDescription,
                    declaredCollateralValue, principal, termMonths, repaymentMethod, null);
        }
    }

    /**
     * 금융자산담보대출 신청 커맨드(Phase 2 실연동).
     *
     * @param collateralType        금융자산 계열 담보 유형(DEPOSIT·BOND·EQUITY)
     * @param declaredCollateralValue 신청자가 제시한 평가액 — 시가 조회 불가 시 폴백
     * @param assetCode             위성 조회 키 — EQUITY 는 종목코드 6자리, 그 외는 생략 가능
     * @param quantity              수량(주식 수) — EQUITY 시가 평가(단가×수량)에 사용
     */
    record FinancialAssetCommand(Long borrowerUserId, String borrowerName, String registrationNo,
                                 CollateralType collateralType,
                                 String collateralDescription, BigDecimal declaredCollateralValue,
                                 String assetCode, Long quantity,
                                 BigDecimal principal, int termMonths, RepaymentMethod repaymentMethod) {
    }

    /**
     * 개인신용대출 신청 커맨드.
     *
     * @param cbScore 외부 CB 점수 — 담보가 없으므로 유일한 심사 근거이며 신청 시점 스냅샷으로 보존된다
     */
    record PersonalCreditCommand(Long borrowerUserId, String borrowerName, String registrationNo,
                                 BigDecimal principal, int termMonths, RepaymentMethod repaymentMethod,
                                 int cbScore) {
    }

    /** 주택담보대출 신청 — 담보 설정(PLEDGED) + 유효담보가치×LTV 한도 검증. */
    SecuredLoan requestMortgage(MortgageCommand command);

    /** 금융자산담보대출 신청 — 시가 평가(EQUITY 는 market 실연동) + 유형별 인정비율 한도 검증. */
    SecuredLoan requestFinancialAsset(FinancialAssetCommand command);

    /** 개인신용대출 신청 — CB 점수 → 등급 → 정액 한도 검증. */
    SecuredLoan requestPersonalCredit(PersonalCreditCommand command);
}
