package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.in.ClearScheduledResidualUseCase;
import github.lms.lemuel.account.application.port.out.AppendAccountEntryPort;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.ScheduledResidualClearing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * cut-over 잔존 정산예정금 청산 백필 유스케이스 — ADR 0026 Option A.
 *
 * <p>전체 전표를 읽어 셀러별 SETTLEMENT_SCHEDULED 순차변 잔액을 계산({@link ScheduledResidualClearing})하고,
 * 잔액이 남은 셀러마다 {@code DR CASH / CR SETTLEMENT_SCHEDULED} 청산분개를 적재한다. 적재는 자연키
 * {@code (source_topic, ref_type, ref_id=sellerId)} UNIQUE 로 멱등이며, 청산분개가 잔액을 상계하므로
 * 반복 실행하면 계획이 비어(추가 청산 0건) 결과가 불변이다.
 */
@Service
public class ClearScheduledResidualService implements ClearScheduledResidualUseCase {

    private final LoadAccountEntryPort loadAccountEntryPort;
    private final AppendAccountEntryPort appendAccountEntryPort;

    public ClearScheduledResidualService(LoadAccountEntryPort loadAccountEntryPort,
                                         AppendAccountEntryPort appendAccountEntryPort) {
        this.loadAccountEntryPort = loadAccountEntryPort;
        this.appendAccountEntryPort = appendAccountEntryPort;
    }

    @Override
    @Transactional
    public ClearingReport clearResidual() {
        List<AccountEntry> plan = ScheduledResidualClearing.plan(loadAccountEntryPort.findAll());
        BigDecimal total = BigDecimal.ZERO;
        for (AccountEntry clearing : plan) {
            appendAccountEntryPort.append(clearing); // 자연키 멱등 — 이미 있으면 no-op
            total = total.add(clearing.getAmount());
        }
        return new ClearingReport(plan.size(), total);
    }
}
