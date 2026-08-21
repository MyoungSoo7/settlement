package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointHold;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 포인트 선점 적재·저장 포트.
 *
 * <p>조회를 <b>참조({@code referenceType}/{@code referenceId})로만</b> 한다 — 확정·해제를 부르는
 * 쪽(결제 만료 배치·입금 확인)은 tender 만 쥐고 있고 어느 계정 것인지는 모른다. 계정은 선점
 * 레코드가 알려 준다. 호출자가 넘긴 계정을 믿고 잠금을 풀면, 남의 계정 잠금을 푸는 통로가 된다.
 */
public interface PointHoldPort {

    PointHold save(PointHold hold);

    /** 근거로 선점을 되찾는다. 상태와 무관하게 찾는다 — 이미 해소된 건을 다시 부르는 것도 멱등 경로다. */
    Optional<PointHold> findByReference(String referenceType, String referenceId);

    /**
     * 계정이 지금 잠그고 있는 총액 — 3자 대조가 {@code point_accounts.locked} 와 맞춰 보는 값.
     * 선점이 없으면 0 을 돌려준다(null 아님).
     */
    BigDecimal activeAmount(Long accountId);
}
