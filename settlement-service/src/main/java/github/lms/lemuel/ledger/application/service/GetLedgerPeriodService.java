package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.GetLedgerPeriodUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerPeriodPort;
import github.lms.lemuel.ledger.domain.LedgerPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

/**
 * 원장 기간 상태 조회 서비스 — 행이 없으면 암묵적 OPEN(미영속)을 반환한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetLedgerPeriodService implements GetLedgerPeriodUseCase {

    private final LoadLedgerPeriodPort loadPeriodPort;

    @Override
    public LedgerPeriod getStatus(YearMonth period) {
        if (period == null) {
            throw new IllegalArgumentException("period 필수");
        }
        return loadPeriodPort.findByPeriod(period).orElseGet(() -> LedgerPeriod.open(period));
    }
}
