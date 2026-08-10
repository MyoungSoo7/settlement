package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.CardStatement;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 청구서 신규 개설 유스케이스 — 청구주기별로 카드계정당 1개.
 *
 * <p>매입 확정 시 해당 청구주기의 OPEN 명세서가 없으면 생성한다.
 * 이미 존재하면 기존 명세서를 반환한다(멱등).
 */
public interface OpenCardStatementUseCase {

    /**
     * 지정 카드계정·청구주기의 OPEN 명세서를 찾거나 새로 생성한다.
     *
     * @param cardAccountId    카드계정 ID
     * @param billingYearMonth 청구주기
     * @param dueDate          납부 만기일
     * @return 신규 또는 기존 OPEN 명세서
     */
    CardStatement getOrOpenStatement(Long cardAccountId, YearMonth billingYearMonth, LocalDate dueDate);
}
