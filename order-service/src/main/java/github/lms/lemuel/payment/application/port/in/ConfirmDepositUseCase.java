package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.PaymentDomain;

/**
 * 입금 확인 — 가상계좌·무통장 결제에 돈이 실제로 들어왔을 때 결제를 확정한다.
 *
 * <p>실 운영에서는 PG 입금 통보(웹훅)가 이 경로를 부른다. 지금은 그 연동이 없어 내부·관리 호출만
 * 있고, 그래서 <b>멱등</b>이 특히 중요하다 — 웹훅은 같은 통보를 여러 번 보내는 것이 정상이다.
 *
 * <p>여기서 비로소 일어나는 일: 외부 PG 텐더 매입, 포인트 선점 확정(로트 소비·USE 엔트리),
 * 주문 PAID 전이, {@code payment.captured} 발행. 결제 생성 시점에는 이 중 <b>아무것도</b> 하지
 * 않는다 — 하면 입금되지 않은 주문이 정산 대상으로 넘어간다.
 */
public interface ConfirmDepositUseCase {

    /**
     * @param paymentId   확정할 결제
     * @param actorUserId 결제 주체 — 포인트 선점 확정의 감사 주체로 쓰인다
     * @return 확정된 결제. 이미 확정된 건이면 그 상태 그대로(멱등)
     */
    PaymentDomain confirmDeposit(Long paymentId, Long actorUserId);
}
