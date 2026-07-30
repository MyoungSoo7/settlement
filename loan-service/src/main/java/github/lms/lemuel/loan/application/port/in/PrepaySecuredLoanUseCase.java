package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.SecuredLoan;

import java.math.BigDecimal;

/**
 * 담보/개인신용 대출 중도상환 인바운드 포트.
 *
 * <p>회차 상환({@link RepaySecuredLoanUseCase})과 분리한 이유: 중도상환은 이자 납입이 없는 대신
 * <b>중도상환수수료</b>(부과기간 1095일 잔여 비례, 경과 3년 면제)가 붙는 별개의 요금 체계라, 한 포트에 합치면
 * 호출 측이 수수료 유무를 분기해야 해 요금 정책이 응용 밖으로 샌다.
 */
public interface PrepaySecuredLoanUseCase {

    /**
     * 중도상환 — 원금을 앞당겨 갚고 수수료를 함께 수취한다.
     *
     * @param loanId          대상 대출
     * @param requesterUserId JWT 주체에서 파생한 요청자(소유권 대조, IDOR 방지)
     * @param amount          중도상환액(양수). 잔액 초과분은 잔액으로 clamp 된다.
     */
    PrepayResult prepay(Long loanId, Long requesterUserId, BigDecimal amount);

    /**
     * 중도상환 결과 — 실제 차감액과 수수료를 구조적으로 보존한다(응답·전표의 단일 근거).
     *
     * @param prepaidAmount 실제 차감된 원금(요청액이 잔액을 넘으면 잔액)
     * @param fee           중도상환수수료(면제·0원이면 0)
     */
    record PrepayResult(SecuredLoan loan, BigDecimal prepaidAmount, BigDecimal fee) { }
}
