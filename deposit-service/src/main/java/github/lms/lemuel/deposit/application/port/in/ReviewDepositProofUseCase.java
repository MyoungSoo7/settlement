package github.lms.lemuel.deposit.application.port.in;

import github.lms.lemuel.deposit.domain.DepositProof;

/**
 * 예치금 증빙 운영자 리뷰 유스케이스 — NEEDS_REVIEW(신뢰도 미달·이체일 판독 불가)를 육안 대조로
 * 종결한다.
 */
public interface ReviewDepositProofUseCase {

    DepositProof review(ReviewProofCommand command);

    /**
     * @param proofId    증빙 식별자
     * @param reviewerId 리뷰어 userId — JWT 주체에서 파생 (ADMIN)
     * @param matched    true=대사 확정(MATCHED) / false=반려(MISMATCHED)
     * @param note       육안 대조 근거
     */
    record ReviewProofCommand(Long proofId, Long reviewerId, boolean matched, String note) {
    }
}
