package github.lms.lemuel.recovery.application.port.in;

import java.time.OffsetDateTime;

/**
 * 정체된 지급후 회수 채권을 수기 대응으로 이관하는 유스케이스 (seed-p0-6 후속).
 *
 * <p>OPEN 채권은 후속 정산 확정({@link OffsetSellerRecoveryUseCase}) 때만 자동 상계된다 —
 * 셀러가 활동을 멈추면(휴면·이탈) 자동 상계 기회 자체가 오지 않아 채권이 영구히 OPEN 으로 남는다.
 * 이 유스케이스는 그런 채권을 찾아 {@code MANUAL_REQUIRED} 로 이관해 운영자가 별도 채권 추심
 * 절차(직접 연락·상계·상각 판단 등)를 밟을 수 있게 한다.
 */
public interface EscalateStaleRecoveryUseCase {

    /**
     * OPEN 채권 중 마지막 활동(발생 시각, 또는 그 이후 상계가 있었다면 최근 상계 시각) 이후
     * 설정된 유예 기간이 지나도록 상계가 없었던 건을 전부 {@code MANUAL_REQUIRED} 로 이관한다.
     *
     * @param now 기준 시각(배치 호출 시각, tz-aware) — 유예 기간을 빼 cutoff 를 계산한다.
     * @return 이관된 채권 수
     */
    int escalateStaleOpenRecoveries(OffsetDateTime now);
}
