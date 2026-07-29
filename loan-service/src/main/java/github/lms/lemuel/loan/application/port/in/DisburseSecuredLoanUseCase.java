package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.SecuredLoan;

/**
 * 담보/개인신용 대출 승인·실행 인바운드 포트(운영자 경로).
 */
public interface DisburseSecuredLoanUseCase {

    /** 승인 — 담보형이면 담보를 유효(ACTIVE)로 전이시킨다. */
    SecuredLoan approve(Long loanId);

    /** 거절 — 담보형이면 설정된 담보를 말소(RELEASED)한다. */
    SecuredLoan reject(Long loanId);

    /** 실행(지급) — 비관적 락 + 전표 + Outbox 이벤트가 한 트랜잭션. */
    SecuredLoan disburse(Long loanId);
}
