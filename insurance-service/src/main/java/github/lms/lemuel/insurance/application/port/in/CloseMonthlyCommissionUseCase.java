package github.lms.lemuel.insurance.application.port.in;

import java.time.YearMonth;

/**
 * 월 수수료 마감 배치 유스케이스.
 *
 * <p>대상 월에 지급(paid_at 기준)된 수수료를 FC별로 합산해 append-only 마감 스냅샷
 * ({@code commission_closings})으로 확정하고, FC 단위로
 * {@code lemuel.insurance.commission_monthly_closed} 를 발행한다.
 *
 * <p><b>멱등</b>: 이미 마감된 (fc, 월)은 스킵한다 — 배치 재실행이 안전하다.
 * DB UNIQUE(fc_id, closing_month) 가 최후 방어(L3)다.
 */
public interface CloseMonthlyCommissionUseCase {

    MonthlyClosingResult closeMonth(YearMonth month);

    /**
     * @param closedFcCount  이번 실행으로 새로 마감된 FC 수
     * @param skippedFcCount 이미 마감돼 있어 스킵된 FC 수
     */
    record MonthlyClosingResult(int closedFcCount, int skippedFcCount) {
    }
}
