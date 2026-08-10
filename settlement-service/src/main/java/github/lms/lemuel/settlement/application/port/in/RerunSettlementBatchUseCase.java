package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.rerun.SettlementRerunReport;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunScope;

import java.time.LocalDate;

/**
 * 정산 배치 재실행 유스케이스 — 스케줄 실행이 실패했거나 과거 일자를 다시 돌려야 할 때
 * 운영자가 DB 를 직접 건드리지 않고 복구할 수 있게 한다.
 *
 * <p>모든 단계는 멱등하다: 확정은 REQUESTED 만 읽고, 홀드백 해제는 해제일 도래분만 대상이며,
 * 지급 실행은 REQUESTED Payout 만 집는다. 따라서 재실행이 중복 정산/중복 송금을 만들지 않는다.
 */
public interface RerunSettlementBatchUseCase {

    /**
     * 지정한 단계를 재실행한다.
     *
     * @param scope      재실행 단계 ({@code ALL} 은 재계산 경로만 전개 — 송금 제외)
     * @param targetDate 대상 일자. {@code null} 이면 어제(KST)로 보정한다.
     * @return 단계별 실행 결과 (부분 실패를 포함할 수 있다)
     */
    SettlementRerunReport rerun(SettlementRerunScope scope, LocalDate targetDate);
}
