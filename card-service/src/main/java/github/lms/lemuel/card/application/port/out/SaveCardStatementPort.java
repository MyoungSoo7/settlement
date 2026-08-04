package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardStatement;

/** 청구서 저장 포트. */
public interface SaveCardStatementPort {

    /** 신규(id null)면 INSERT, 기존이면 @Version 낙관적 락 갱신. 영속 id 가 채워진 명세서를 반환. */
    CardStatement save(CardStatement statement);

    /**
     * 납부 레코드를 저장한다 — 멱등 자연키({@code paymentId}) 로 중복 납부를 차단한다.
     *
     * @param statementId 납부 대상 명세서 ID
     * @param paymentId   멱등 자연키
     * @param amount      납부 금액
     */
    void savePayment(Long statementId, String paymentId, java.math.BigDecimal amount);
}
