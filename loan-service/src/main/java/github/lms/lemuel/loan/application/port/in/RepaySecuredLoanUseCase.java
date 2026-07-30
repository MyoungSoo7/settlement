package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.SecuredLoan;

import java.math.BigDecimal;

/**
 * 담보/개인신용 대출 회차 상환 인바운드 포트.
 *
 * <p>원금과 이자를 <b>분리해 받는다</b> — 장기 분할상환은 회차 납입액이 원금+이자로 구성되고 회계상
 * 서로 다른 계정으로 흐르기 때문이다(원금은 대출채권 감소, 이자는 수익 인식). 합계만 받으면 응용
 * 계층이 임의로 쪼개야 해서 상환표와 원장이 어긋난다.
 */
public interface RepaySecuredLoanUseCase {

    /**
     * @param requesterUserId 요청 주체(JWT) — 대출 소유자와 대조해 타인 대출 상환을 차단한다(IDOR 방지)
     * @param principalPortion 원금 상환분(양수)
     * @param interestPortion  이자 상환분(0 이상 — 무이자 회차 허용)
     */
    SecuredLoan repay(Long loanId, Long requesterUserId,
                      BigDecimal principalPortion, BigDecimal interestPortion);
}
