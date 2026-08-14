package github.lms.lemuel.payout.application.port.in;

public interface ExecutePayoutUseCase {

    /**
     * REQUESTED 상태 Payout 들을 일괄 펌뱅킹 호출.
     * 한도 초과는 skip (다음 영업일에 재시도). 한 건이라도 실패 시 다른 건은 영향 없음.
     *
     * @return 성공 / 실패 / 한도초과 건수
     */
    ExecutionReport executeAllPending();

    /**
     * 실행 없이 "이번 배치가 무엇을 얼마나 보낼지" 만 산출한다.
     *
     * <p>펌뱅킹은 되돌리기 어려운 외부 송금이라, 운영자가 규모와 밀릴 사유를 먼저 보고 확정할 수 있어야 한다.
     * 아무 상태도 바꾸지 않는다.
     */
    PayoutPreview previewPending();

    record ExecutionReport(int succeeded, int failed, int limitedSkipped) { }

    /**
     * 미리보기 결과.
     *
     * @param sendableAmount 이번 배치에서 실제로 나갈 금액 합
     * @param limitedAmount  한도로 다음 영업일에 밀릴 금액 합
     */
    record PayoutPreview(int sendableCount, java.math.BigDecimal sendableAmount,
                         int limitedCount, java.math.BigDecimal limitedAmount,
                         java.util.List<PayoutPreviewLine> lines) { }

    /** 미리보기 1건 — 밀린 건은 {@code reason} 에 사유가 담긴다. */
    record PayoutPreviewLine(Long payoutId, Long sellerId, java.math.BigDecimal amount,
                             boolean sendable, String reason) { }
}
