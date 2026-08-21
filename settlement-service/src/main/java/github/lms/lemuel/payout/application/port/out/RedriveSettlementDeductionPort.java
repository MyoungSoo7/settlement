package github.lms.lemuel.payout.application.port.out;

/**
 * 지급 백필이 <b>확정 경로의 종착점을 재구동</b>하기 위해 필요로 하는 능력 — payout 이 소유하는 요구 포트.
 *
 * <p>구현은 정산 확정 로직을 가진 settlement 슬라이스가 제공한다
 * ({@code settlement.adapter.in.internal.PayoutDeductionRedriveBridge}).
 * payout 이 settlement 의 유스케이스를 직접 부르면 payout→settlement 간선이 생기고,
 * 이미 있는 settlement→payout 과 만나 순환이 된다. 인터페이스를 payout 이 소유하고 구현을 settlement 이
 * 제공하면 결합은 settlement→payout 한 방향으로 모인다.
 */
public interface RedriveSettlementDeductionPort {

    /**
     * 기록된 대출 차감을 근거로 확정 경로를 재구동한다.
     *
     * @return 차감 기록이 없으면(= loan 이벤트가 아직/영영 도착하지 않음) {@code false} — 이때는
     *     차감액을 알 수 없으므로 <b>지급을 만들지 않는다</b>. 모르는 채 지급하면 대출채권이 사라진다.
     */
    boolean redriveFromRecordedDeduction(long settlementId, long sellerId);
}
