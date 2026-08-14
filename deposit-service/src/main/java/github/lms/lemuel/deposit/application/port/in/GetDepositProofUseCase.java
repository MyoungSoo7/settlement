package github.lms.lemuel.deposit.application.port.in;

import github.lms.lemuel.deposit.domain.DepositProof;

import java.util.Optional;

/**
 * 예치금 증빙 조회 유스케이스.
 */
public interface GetDepositProofUseCase {

    /** 참조의 최신 증빙 — 기표 게이트가 보는 것과 같은 기준. */
    Optional<DepositProof> latestForReference(Long sellerId, String referenceType, String referenceId);
}
