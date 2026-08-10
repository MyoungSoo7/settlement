package github.lms.lemuel.insurance.application.port.in;

import java.time.LocalDate;

/**
 * 수수료 회차 지급 배치 유스케이스.
 *
 * <p>D4 선지급 스케줄에서 지급 예정일(due_date)이 도래한 SCHEDULED 회차를 지급(PAID)한다.
 *
 * <p><b>계약 상태 게이트</b>: ACTIVE 계약의 회차만 지급한다.
 * LAPSED(실효 중)는 부활 여지가 있으므로 지급 보류(다음 배치가 재시도),
 * terminal 계약의 미지급 회차는 환수 스윕({@link SweepCommissionClawbacksUseCase})이 소멸시킨다.
 */
public interface PayDueCommissionsUseCase {

    PayoutBatchResult payDueOn(LocalDate today);

    /**
     * @param paid 지급 완료된 회차 수 (SCHEDULED → PAID)
     * @param held 계약이 ACTIVE 가 아니어서 보류된 회차 수 (상태 불변)
     */
    record PayoutBatchResult(int paid, int held) {
    }
}
