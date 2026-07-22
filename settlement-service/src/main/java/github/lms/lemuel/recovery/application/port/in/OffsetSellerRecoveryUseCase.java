package github.lms.lemuel.recovery.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 후속 정산 확정 시 미상계 채권을 지급액에서 상계하는 유스케이스 (seed-p0-6).
 *
 * <p>확정 청크 트랜잭션 안에서 호출된다 — 셀러의 OPEN 채권을 오래된 순으로 잠그고 소진하며,
 * 상계 이력·상계 분개(Dr AP / Cr AR)를 같은 커밋에 남긴다. 반환값만큼 즉시지급 Payout 요청액을
 * 줄이는 것은 호출자(확정 라이터)의 몫이다.
 */
public interface OffsetSellerRecoveryUseCase {

    /**
     * @param immediateAmount 상계 전 즉시지급 가능액 (0 이하면 상계 없음)
     * @return 상계 총액 (0 ≤ 반환 ≤ immediateAmount). 재실행 시 기존 상계 총액을 그대로 반환(멱등).
     */
    BigDecimal offsetForConfirmedSettlement(Long settlementId, Long sellerId,
                                            BigDecimal immediateAmount, LocalDate settlementDate);
}
