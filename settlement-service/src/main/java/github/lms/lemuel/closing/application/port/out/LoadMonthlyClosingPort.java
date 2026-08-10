package github.lms.lemuel.closing.application.port.out;

import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.SellerMonthlyClosing;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** 월마감 run·마트 조회. */
public interface LoadMonthlyClosingPort {

    Optional<MonthlyClosingRun> findRun(YearMonth period);

    List<SellerMonthlyClosing> findMart(YearMonth period);
}
