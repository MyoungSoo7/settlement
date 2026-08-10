package github.lms.lemuel.closing.application.service;

import github.lms.lemuel.closing.application.dto.MonthlyClosingView;
import github.lms.lemuel.closing.application.port.in.GetMonthlyClosingUseCase;
import github.lms.lemuel.closing.application.port.out.LoadMonthlyClosingPort;
import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.exception.ClosingRunNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

/** 월마감 조회 서비스 — read-only. */
@Service
@RequiredArgsConstructor
public class GetMonthlyClosingService implements GetMonthlyClosingUseCase {

    private final LoadMonthlyClosingPort loadClosingPort;

    @Override
    @Transactional(readOnly = true)
    public MonthlyClosingView getClosing(YearMonth period) {
        MonthlyClosingRun run = loadClosingPort.findRun(period)
                .orElseThrow(() -> new ClosingRunNotFoundException(period));
        return new MonthlyClosingView(run, loadClosingPort.findMart(period));
    }
}
