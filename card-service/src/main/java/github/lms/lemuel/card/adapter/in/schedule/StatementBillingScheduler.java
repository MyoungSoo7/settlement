package github.lms.lemuel.card.adapter.in.schedule;

import github.lms.lemuel.card.application.port.in.CloseStatementUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * 청구서 마감 스케줄러 — 매월 1일 01:00 KST 에 전월 OPEN 명세서를 CLOSED 로 마감한다.
 *
 * <p>{@link SchedulerLock} 으로 다중 인스턴스 중복 실행을 방지한다.
 * 마감 실패해도 던지지 않는다(fail-open) — 다음 실행이 보정한다.
 */
@Component
public class StatementBillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatementBillingScheduler.class);

    private final CloseStatementUseCase closeStatementUseCase;

    public StatementBillingScheduler(CloseStatementUseCase closeStatementUseCase) {
        this.closeStatementUseCase = closeStatementUseCase;
    }

    /**
     * 매월 1일 새벽 01:00 KST 에 전월 명세서 마감.
     * {@code lockAtMostFor=PT30M}: 인스턴스 비정상 종료 시 락 최대 보유 시간.
     */
    @Scheduled(cron = "${app.card.statement.billing-cron:0 0 1 1 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "card-statement-billing", lockAtMostFor = "PT30M")
    public void closePreviousMonthStatements() {
        YearMonth previousMonth = YearMonth.now(ZoneId.of("Asia/Seoul")).minusMonths(1);
        log.info("[StatementBilling] 시작 period={}", previousMonth);
        try {
            List<Long> closed = closeStatementUseCase.closeStatements(previousMonth);
            log.info("[StatementBilling] 완료: 마감 {}건 period={}", closed.size(), previousMonth);
        } catch (RuntimeException e) {
            log.error("[StatementBilling] 배치 실패(fail-open) — 다음 실행에서 재시도", e);
        }
    }
}
