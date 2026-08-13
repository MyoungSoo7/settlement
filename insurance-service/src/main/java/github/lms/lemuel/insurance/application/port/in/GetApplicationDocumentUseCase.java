package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.ApplicationDocument;

import java.util.Optional;

/**
 * 청약서류 조회 유스케이스.
 */
public interface GetApplicationDocumentUseCase {

    /** 청약의 최신 서류 — 승인 게이트가 보는 것과 같은 기준. */
    Optional<ApplicationDocument> latestForApplication(String applicationId);
}
