package github.lms.lemuel.insurance.domain.exception;

import java.time.LocalDate;

/** 유효기간이 지난 가입설계의 청약 전환 시도 — 새 설계를 만들어야 한다. */
public class ProposalExpiredException extends RuntimeException {

    public ProposalExpiredException(String proposalId, LocalDate validUntil) {
        super("유효기간이 지난 가입설계는 청약으로 전환할 수 없습니다: proposalId="
                + proposalId + ", validUntil=" + validUntil);
    }
}
