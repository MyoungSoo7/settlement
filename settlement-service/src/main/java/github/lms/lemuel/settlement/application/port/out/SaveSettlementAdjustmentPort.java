package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.SettlementAdjustment;

/**
 * 역정산 감사 레코드 저장 Outbound Port
 */
public interface SaveSettlementAdjustmentPort {

    SettlementAdjustment save(SettlementAdjustment adjustment);
}
