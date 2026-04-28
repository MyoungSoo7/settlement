package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.TenderType;

import java.math.BigDecimal;
import java.util.List;

public interface CreateSplitPaymentUseCase {

    /**
     * 분할결제 생성. 여러 지불수단으로 1 결제를 처리.
     *
     * <p>Tender 처리 흐름:
     * <ul>
     *   <li>외부 PG tender (CARD/KAKAO_PAY 등): PgRouter 경유 authorize → capture</li>
     *   <li>내부 잔액 tender (POINT/GIFT_CARD): 외부 호출 없이 즉시 capture</li>
     * </ul>
     *
     * <p>일부 tender 가 실패하면 이미 처리된 다른 tender 를 보상 환불 (Saga 형태) — 본 구현은
     * 트랜잭션 안에서 시도하므로 RuntimeException 발생 시 자동 롤백.
     *
     * @param orderId 주문 ID
     * @param tenders 지불수단 라인 목록 (최소 2 개)
     * @return 생성·캡처 완료된 PaymentDomain
     */
    PaymentDomain createSplit(Long orderId, List<TenderRequest> tenders);

    record TenderRequest(TenderType type, BigDecimal amount) {
        public TenderRequest {
            if (type == null) throw new IllegalArgumentException("tender type 필수");
            if (amount == null || amount.signum() <= 0) {
                throw new IllegalArgumentException("amount 양수 필수");
            }
        }
    }
}
