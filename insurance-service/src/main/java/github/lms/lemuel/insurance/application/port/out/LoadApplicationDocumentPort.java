package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.ApplicationDocument;

import java.util.Optional;

/**
 * 청약서류 조회 포트.
 */
public interface LoadApplicationDocumentPort {

    Optional<ApplicationDocument> findById(Long id);

    /** 멱등 선조회 — 같은 파일 재업로드를 OCR 호출 전에 잡는다. */
    Optional<ApplicationDocument> findByApplicationIdAndFileHash(String applicationId, String fileHash);

    /** 청약의 최신 서류(업로드 시각 기준) — 승인 게이트의 판정 대상. */
    Optional<ApplicationDocument> findLatestByApplicationId(String applicationId);
}
