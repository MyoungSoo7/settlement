package github.lms.lemuel.chargeback.application.port.out;

import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackStatus;

import java.util.List;
import java.util.Optional;

public interface LoadChargebackPort {

    Optional<Chargeback> findById(Long id);

    /** 멱등 — PG webhook 재전송 시 중복 OPEN 방지. */
    Optional<Chargeback> findByPgChargebackId(String pgChargebackId);

    /** 같은 결제에 대한 분쟁 이력. */
    List<Chargeback> findByPaymentId(Long paymentId);

    /** 정산 미연결(settlement_id IS NULL) 분쟁 — 정산 생성 시 백필 대상 조회. */
    List<Chargeback> findUnlinkedByPaymentId(Long paymentId);

    /** 운영자 콘솔 — 상태별 페이지 조회. */
    List<Chargeback> findByStatus(ChargebackStatus status, int limit);
}
