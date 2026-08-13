package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CollateralDocument;

/**
 * 담보서류 저장 포트.
 */
public interface SaveCollateralDocumentPort {

    /** 신규 서류 + 파일 본문 저장. (secured_loan_id, file_hash) UNIQUE 가 멱등 최후 방어선이다. */
    CollateralDocument saveNew(CollateralDocument document, byte[] content);

    /** 상태 변경(리뷰 종결) 반영 — 파일 본문·추출값은 불변이라 다시 받지 않는다. */
    CollateralDocument update(CollateralDocument document);
}
