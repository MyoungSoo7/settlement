package github.lms.lemuel.chargeback.application.port.in;

import github.lms.lemuel.chargeback.domain.Chargeback;

/**
 * 운영자 결정 — 분쟁을 ACCEPT 또는 REJECT.
 *
 * <p>ACCEPT 시 부수 효과: 연결된 정산에 대한 {@code SettlementAdjustment} 음수 row 자동 생성
 * (ApplicationService 가 책임). 정산이 아직 없는 분쟁(settlementId == null) 은 ACCEPT 시
 * adjustment 생성을 건너뜀 — 추후 정산 생성 시 백필 책임.
 */
public interface DecideChargebackUseCase {

    Chargeback accept(Long chargebackId, String decidedBy, String note);

    Chargeback reject(Long chargebackId, String decidedBy, String note);
}
