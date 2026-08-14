package github.lms.lemuel.settlement.application.port.out;

import java.time.LocalDate;

/**
 * 정산 확정 배치 실행 아웃바운드 포트.
 *
 * <p>Spring Batch({@code JobOperator})는 프레임워크 관심사라 어댑터에 가둔다 — 애플리케이션
 * 서비스는 "확정 배치를 이 날짜로 돌린다"는 의도만 안다.
 */
public interface RunSettlementConfirmBatchPort {

    /**
     * 확정 배치를 동기 실행한다.
     *
     * @param targetDate 확정 대상 일자
     * @return 종료 상태와 읽기/쓰기 건수
     */
    BatchRunResult runFor(LocalDate targetDate);

    /**
     * @param status  Batch 종료 상태 문자열 (COMPLETED 만 성공으로 본다)
     * @param read    리더가 읽은 건수
     * @param written 라이터가 확정한 건수
     */
    record BatchRunResult(String status, long read, long written) {

        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }
    }
}
