package github.lms.lemuel.recovery.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 지급후 회수가 <b>미해제 홀드백에서 우선 흡수</b>하기 위해 필요로 하는 능력 — recovery 가 소유하는 요구 포트.
 *
 * <p>이전에는 recovery 의 애플리케이션 서비스가 settlement 의 출력 포트 4종을 직접 주입받아
 * {@code Settlement} 애그리거트를 <b>로드·변경·저장하고 정산 도메인 이벤트까지 발행</b>했다.
 * 다른 슬라이스의 애그리거트를 탐색하다 바꾸는 구조라, 홀드백 소진 규칙이 정산 밖에서 실행되고
 * recovery→settlement 간선이 순환({@code recovery → settlement → recovery})의 한쪽 다리가 됐다.
 *
 * <p>이제 recovery 는 "얼마를 흡수해 달라"만 말하고, 얼마나 흡수됐는지만 돌려받는다.
 * 애그리거트를 여는 일은 그것을 소유한 settlement 슬라이스 안에서만 일어난다.
 *
 * <p>구현은 {@code settlement.adapter.in.internal.RecoveryHoldbackAbsorptionBridge} 가 제공한다.
 */
public interface AbsorbSettlementHoldbackPort {

    /**
     * 정산의 미해제 홀드백에서 {@code recoveredAmount} 까지 흡수한다.
     *
     * <p>호출자의 트랜잭션에 합류한다 — 흡수와 채권 발생은 한 트랜잭션이어야 한다.
     *
     * @return 정산을 찾지 못했거나 셀러를 해석하지 못하면 {@link Optional#empty()}
     *     (호출자는 아무것도 바꾸지 않고 종료한다). 그 외에는 흡수 결과 — 흡수액이 0일 수 있다.
     */
    Optional<HoldbackAbsorption> absorbForRecovery(Long settlementId, Long adjustmentId, BigDecimal recoveredAmount);

    /**
     * @param sellerId 정산이 해석한 소유 셀러 — 채권 귀속처
     * @param absorbed 실제로 홀드백에서 깎인 금액(0 이상)
     */
    record HoldbackAbsorption(Long sellerId, BigDecimal absorbed) {
    }
}
