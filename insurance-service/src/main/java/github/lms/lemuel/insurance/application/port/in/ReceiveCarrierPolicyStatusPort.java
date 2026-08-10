package github.lms.lemuel.insurance.application.port.in;

/**
 * 외부 보험사(carrier)로부터 계약 상태 통보를 수신하는 인바운드 포트.
 *
 * <p><b>D1 — System of Record + No-op 설계</b>:
 * 현재 외부 보험사 연동은 존재하지 않는다(insurance-service 가 SoR). 훗날 보험사가 상태를
 * 통보해올 수 있도록 이 포트를 미리 선언한다. 구현체는 {@code NoOpCarrierPolicyStatusService}
 * 하나만 있으며, 외부 HTTP 호출 코드는 작성하지 않는다.
 *
 * <p>Kafka 컨슈머(adapter/in/kafka)는 이 포트를 호출한다 — 연동 경로가 생기면 구현체만 교체한다.
 */
public interface ReceiveCarrierPolicyStatusPort {

    /**
     * 보험사 통보 수신 처리.
     *
     * @param policyNumber  계약 번호(증권번호)
     * @param carrierStatus 보험사에서 통보한 상태 코드 (자유 문자열 — 표준화는 미래 작업)
     */
    void onCarrierPolicyStatusReceived(String policyNumber, String carrierStatus);
}
