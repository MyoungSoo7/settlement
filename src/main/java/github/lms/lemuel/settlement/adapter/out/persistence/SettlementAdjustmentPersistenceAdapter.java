package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementAdjustmentPersistenceAdapter implements SaveSettlementAdjustmentPort {

    private final SpringDataSettlementAdjustmentJpaRepository repository;

    @Override
    public SettlementAdjustment save(SettlementAdjustment adjustment) {
        SettlementAdjustmentJpaEntity saved =
                repository.save(SettlementAdjustmentMapper.toJpa(adjustment));
        if (adjustment.getId() == null && saved.getId() != null) {
            adjustment.assignId(saved.getId());
        }
        return adjustment;
    }
}
