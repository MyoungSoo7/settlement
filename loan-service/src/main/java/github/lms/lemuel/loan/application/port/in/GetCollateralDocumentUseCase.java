package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CollateralDocument;

import java.util.Optional;

/**
 * 담보서류 조회 유스케이스.
 */
public interface GetCollateralDocumentUseCase {

    /** 대출의 최신 담보서류 — 승인 게이트가 보는 것과 같은 기준. */
    Optional<CollateralDocument> latestForLoan(Long loanId);
}
