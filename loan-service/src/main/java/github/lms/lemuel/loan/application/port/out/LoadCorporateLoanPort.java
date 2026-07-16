package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CorporateLoan;

import java.util.List;
import java.util.Optional;

/**
 * 기업 신용대출 조회 아웃바운드 포트.
 */
public interface LoadCorporateLoanPort {

    Optional<CorporateLoan> findById(Long loanId);

    /** 실행(disburse) 전용 — 비관적 락으로 조회해 동시 이중지급을 차단한다. */
    Optional<CorporateLoan> findByIdForUpdate(Long loanId);

    /** 특정 종목의 기업대출 목록(최신순). */
    List<CorporateLoan> findByStockCode(String stockCode);

    /** 전체 기업대출 최신순 상위 {@code limit} 건. */
    List<CorporateLoan> findRecent(int limit);

    /** 신청자(ownerUserId) 본인 기업대출 최신순 상위 {@code limit} 건 — USER(CEO) 소유권 스코핑용. */
    List<CorporateLoan> findByOwner(Long ownerUserId, int limit);
}
