package github.lms.lemuel.settlement.application.port.out;

/**
 * 정산 생성 직후 사전분쟁(chargeback) 연결 백필 아웃 포트.
 *
 * <p>settlement 컨텍스트는 chargeback 도메인/애플리케이션을 직접 import 하지 않는다 —
 * 이 포트의 구현 어댑터가 chargeback 유스케이스로 위임한다(같은 서비스 내 컨텍스트 경계,
 * {@code ChargebackService} 가 settlement 조정 포트를 쓰는 방향의 대칭).
 */
public interface BackfillChargebackSettlementLinkPort {

    /** 해당 결제의 정산 미연결 분쟁을 연결하고 ACCEPTED 건의 환수 조정을 생성한다. @return 연결 건수 */
    int backfillChargebacks(Long paymentId, Long settlementId);
}
