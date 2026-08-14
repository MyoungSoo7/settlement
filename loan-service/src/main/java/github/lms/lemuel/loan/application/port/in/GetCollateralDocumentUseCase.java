package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.CollateralDocumentStatus;

import java.util.List;
import java.util.Optional;

/**
 * 담보서류 조회 유스케이스.
 */
public interface GetCollateralDocumentUseCase {

    /** 대출의 최신 담보서류 — 승인 게이트가 보는 것과 같은 기준. */
    Optional<CollateralDocument> latestForLoan(Long loanId);

    /** 상태별 목록(최신 우선) — 리뷰 큐 화면용. */
    List<CollateralDocument> byStatus(CollateralDocumentStatus status, int limit);
}
