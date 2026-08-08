package github.lms.lemuel.closing.application.port.in;

import github.lms.lemuel.closing.application.dto.MonthlyClosingView;

import java.time.YearMonth;

/** 월마감 조회 — run 요약 + 셀러 마트. */
public interface GetMonthlyClosingUseCase {

    /**
     * @throws github.lms.lemuel.closing.domain.exception.ClosingRunNotFoundException
     *         해당 월의 마감이 아직 실행된 적 없는 경우
     */
    MonthlyClosingView getClosing(YearMonth period);
}
