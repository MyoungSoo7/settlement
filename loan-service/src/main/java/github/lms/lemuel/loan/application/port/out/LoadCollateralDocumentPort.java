package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CollateralDocument;

import java.util.Optional;

/**
 * 담보서류 조회 포트.
 */
public interface LoadCollateralDocumentPort {

    Optional<CollateralDocument> findById(Long id);

    /** 멱등 선조회 — 같은 파일 재업로드를 OCR 호출 전에 잡는다. */
    Optional<CollateralDocument> findByLoanIdAndFileHash(Long securedLoanId, String fileHash);

    /** 대출의 최신 서류(업로드 시각 기준) — 승인 게이트의 판정 대상. */
    Optional<CollateralDocument> findLatestByLoanId(Long securedLoanId);
}
