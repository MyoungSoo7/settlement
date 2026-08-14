package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.ProposalSummary;

/**
 * 가입설계 단건 조회 — 설계 작성자 본인만 볼 수 있다(소유권 대조).
 *
 * @see github.lms.lemuel.insurance.domain.exception.ProposalOwnershipException
 */
public interface GetProposalUseCase {

    /** @param requesterFcId 조회 요청자 — JWT 주체에서 파생된 FC 식별자 */
    ProposalSummary get(String proposalId, String requesterFcId);
}
