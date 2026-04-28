package github.lms.lemuel.payout.application.port.in;

public interface ExecutePayoutUseCase {

    /**
     * REQUESTED 상태 Payout 들을 일괄 펌뱅킹 호출.
     * 한도 초과는 skip (다음 영업일에 재시도). 한 건이라도 실패 시 다른 건은 영향 없음.
     *
     * @return 성공 / 실패 / 한도초과 건수
     */
    ExecutionReport executeAllPending();

    record ExecutionReport(int succeeded, int failed, int limitedSkipped) { }
}
