package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.ProposalQuote;

/** 가입설계 저장 포트 — 신규 INSERT 와 전이 결과 반영을 구분한다. */
public interface SaveProposalPort {

    ProposalQuote insertNew(ProposalQuote proposal);

    ProposalQuote update(ProposalQuote proposal);
}
