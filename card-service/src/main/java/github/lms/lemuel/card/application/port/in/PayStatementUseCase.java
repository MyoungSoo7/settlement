package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.CardStatement;

import java.math.BigDecimal;

/**
 * 청구서 상환 유스케이스.
 *
 * <p>내부 REST({@code POST /internal/api/v1/statements/{id}/payments})를 통해 호출된다.
 * {@code paymentId} 를 멱등 키로 사용해 동일 상환을 중복 적용하지 않는다.
 *
 * <p>전액 상환 시:
 * <ul>
 *   <li>명세서 상태 → PAID</li>
 *   <li>카드계정이 DELINQUENT 상태였다면 ACTIVE 로 자동 복구</li>
 *   <li>{@code lemuel.card.statement_paid} Outbox 이벤트 발행</li>
 * </ul>
 */
public interface PayStatementUseCase {

    /**
     * 명세서 상환.
     *
     * @param command 상환 커맨드(statementId, paymentId 멱등 키, amount)
     * @return 갱신된 명세서
     */
    CardStatement pay(PayStatementCommand command);

    /**
     * @param statementId 상환 대상 명세서 ID
     * @param paymentId   멱등 자연키 — 동일 paymentId 로 이미 처리된 상환은 중복 적용 안 함
     * @param amount      상환 금액(양수)
     */
    record PayStatementCommand(Long statementId, String paymentId, BigDecimal amount) {
    }
}
