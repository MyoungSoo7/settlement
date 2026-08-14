package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositProof;

import java.util.Optional;

/**
 * 예치금 증빙 조회 포트.
 */
public interface LoadDepositProofPort {

    Optional<DepositProof> findById(Long id);

    /** 멱등 선조회 — 같은 파일 재업로드를 OCR 호출 전에 잡는다. */
    Optional<DepositProof> findByReferenceAndFileHash(Long sellerId, String referenceType,
                                                      String referenceId, String fileHash);

    /** 참조의 최신 증빙(업로드 시각 기준) — 기표 게이트의 판정 대상. */
    Optional<DepositProof> findLatestByReference(Long sellerId, String referenceType, String referenceId);
}
