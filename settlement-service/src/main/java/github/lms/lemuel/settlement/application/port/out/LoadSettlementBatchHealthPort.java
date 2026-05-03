package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.application.dto.SettlementBatchHealthSnapshot;

import java.time.LocalDate;

/**
 * Settlement Batch Health 조회 Port (Outbound)
 * 배치 헬스 체크를 위한 데이터 조회 포트
 */
public interface LoadSettlementBatchHealthPort {

    /**
     * 특정 날짜의 배치 헬스 스냅샷 조회
     *
     * @param date 조회할 날짜
     * @return 배치 헬스 스냅샷 (정산 대기/확정 건수, 조정 대기 건수 포함)
     */
    SettlementBatchHealthSnapshot loadHealthSnapshot(LocalDate date);
}
