package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.ProposalQuote;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 가입설계 조회 포트. */
public interface LoadProposalPort {

    Optional<ProposalQuote> findByProposalId(String proposalId);

    /** 만기 스윕 대상 — QUOTED 이면서 유효기한이 {@code date} 이전(미포함)인 설계. */
    List<ProposalQuote> findQuotedValidUntilBefore(LocalDate date);
}
