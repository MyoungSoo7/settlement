package github.lms.lemuel.card.application.port.in;

import java.time.LocalDate;

/**
 * 연체 명세서 전이 유스케이스.
 *
 * <p>일 1회 배치({@code DelinquencyBatchScheduler})가 호출한다.
 * 만기일(dueDate)이 경과했고 아직 미납인 CLOSED·PARTIALLY_PAID 명세서를
 * DELINQUENT 로 전환하고, 해당 카드계정을 DELINQUENT 로 전이시킨다.
 *
 * <p>연체 카드계정에서는 모든 카드 승인이 {@link github.lms.lemuel.card.domain.DeclineReason#CARD_SUSPENDED}
 * 로 거절된다.
 */
public interface MarkDelinquentStatementsUseCase {

    /**
     * 연체 대상 명세서를 DELINQUENT 로 전이시킨다.
     *
     * @param today 기준일(만기 경과 판단 기준)
     * @return 연체 처리된 명세서(및 계정) 수
     */
    int markDelinquent(LocalDate today);
}
