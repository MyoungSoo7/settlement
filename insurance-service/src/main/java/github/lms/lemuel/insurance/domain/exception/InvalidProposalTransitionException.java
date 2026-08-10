package github.lms.lemuel.insurance.domain.exception;

import github.lms.lemuel.insurance.domain.ProposalStatus;

/** 가입설계 상태머신이 허용하지 않는 전이 시도. */
public class InvalidProposalTransitionException extends RuntimeException {

    public InvalidProposalTransitionException(ProposalStatus from, ProposalStatus to) {
        super("허용되지 않는 가입설계 상태 전이입니다: " + from + " → " + to);
    }
}
