package github.lms.lemuel.card.application.port.in;

import java.time.YearMonth;
import java.util.List;

/**
 * 청구서 마감 유스케이스.
 *
 * <p>청구주기 종료 시 배치({@code StatementBillingScheduler})가 호출한다.
 * 지정 청구주기의 OPEN 명세서를 CLOSED 로 전환한다.
 */
public interface CloseStatementUseCase {

    /**
     * 지정 청구주기의 OPEN 명세서를 마감한다.
     *
     * @param billingYearMonth 마감할 청구주기
     * @return 마감된 명세서 ID 목록
     */
    List<Long> closeStatements(YearMonth billingYearMonth);
}
