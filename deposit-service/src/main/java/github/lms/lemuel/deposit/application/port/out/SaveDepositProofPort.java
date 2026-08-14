package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositProof;

/**
 * 예치금 증빙 저장 포트.
 */
public interface SaveDepositProofPort {

    /** 신규 증빙 + 파일 본문 저장. (앵커, file_hash) UNIQUE 가 멱등 최후 방어선이다. */
    DepositProof saveNew(DepositProof proof, byte[] content);

    /** 상태 변경(지연 대사·리뷰 종결) 반영 — 파일 본문·추출값은 불변이라 다시 받지 않는다. */
    DepositProof update(DepositProof proof);
}
