package github.lms.lemuel.settlement.application.port.in;

import java.time.LocalDate;

public interface ReleaseHoldbackUseCase {

    /**
     * 주어진 날짜 시점에 release 가능한 모든 holdback 을 일괄 해제.
     * 매일 자정 + α 에 배치로 호출 (HoldbackReleaseScheduler).
     *
     * @return 해제된 정산 건수
     */
    int releaseAllDueOn(LocalDate today);
}
