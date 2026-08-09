package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.application.port.in.CreateProposalUseCase.ProposalSummary;

/** 가입설계 단건 조회. */
public interface GetProposalUseCase {

    ProposalSummary get(String proposalId);
}
