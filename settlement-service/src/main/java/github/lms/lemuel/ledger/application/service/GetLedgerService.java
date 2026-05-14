package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.GetLedgerUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetLedgerService implements GetLedgerUseCase {

    private final LoadLedgerEntryPort loadPort;

    @Override
    public List<LedgerEntry> getBySettlementId(Long settlementId) {
        return loadPort.findByReference(settlementId, ReferenceType.SETTLEMENT);
    }

    @Override
    public List<LedgerEntry> getByRefundId(Long refundId) {
        return loadPort.findByReference(refundId, ReferenceType.REFUND);
    }

    @Override
    public List<LedgerEntry> getBySettlementDateBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from, to 모두 필수");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to 가 from 보다 빠를 수 없습니다");
        }
        return loadPort.findBySettlementDateBetween(from, to);
    }
}
