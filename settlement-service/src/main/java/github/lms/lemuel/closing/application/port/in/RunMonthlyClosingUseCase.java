package github.lms.lemuel.closing.application.port.in;

import github.lms.lemuel.closing.domain.MonthlyClosingRun;

import java.time.YearMonth;

/** 정보계 월마감 실행 — 셀러 월 정산 마트 적재 + run 감사 기록. */
public interface RunMonthlyClosingUseCase {

    /**
     * 대상 월의 DONE 정산을 셀러별로 집계해 마트에 적재한다(기간 단위 교체 — 재실행 멱등).
     *
     * @throws github.lms.lemuel.closing.domain.exception.MonthlyClosingLockedException
     *         원장 마감된 기간에 COMPLETED 마트가 이미 있는 경우(확정 수치 보호)
     * @throws github.lms.lemuel.closing.domain.exception.MonthlyClosingFailedException
     *         집계·적재 실패 — FAILED run 기록 후 전파
     */
    MonthlyClosingRun run(YearMonth period, String triggeredBy);
}
