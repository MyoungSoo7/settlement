package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.ReceiveCarrierPolicyStatusPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * D1 — 외부 보험사 통보 수신 No-op 구현체.
 *
 * <p>현재 insurance-service 는 System of Record 로, 외부 보험사와의 실시간 연동 없이
 * 계약 상태를 자체 판정한다. 훗날 보험사 통보를 수신할 수 있도록 포트를 선언하고
 * 이 no-op 를 유일한 구현체로 둔다.
 *
 * <p>외부 HTTP 호출 코드는 작성하지 않는다 — 포트 교체로 충분하다.
 */
@Service
public class NoOpCarrierPolicyStatusService implements ReceiveCarrierPolicyStatusPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpCarrierPolicyStatusService.class);

    /**
     * {@inheritDoc}
     *
     * <p>현재 구현체는 수신 로그만 남기고 아무 상태도 변경하지 않는다(D1 의도적 no-op).
     */
    @Override
    public void onCarrierPolicyStatusReceived(String policyNumber, String carrierStatus) {
        log.info("[D1 no-op] 보험사 상태 통보 수신 — carrierStatus={}", carrierStatus);
        // D1: 외부 보험사 연동 없음. 훗날 이 메서드에서 도메인 상태를 업데이트한다.
    }
}
