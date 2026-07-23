package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.application.dto.TaxSettlementView;

import java.util.Optional;

/**
 * 세무 계산 입력(수수료·순정산액·상태·정산일)을 어댑터 레벨에서만 read 하기 위한 출력 포트.
 * 세무 application 레이어는 settlement 엔티티를 import 하지 않는다.
 */
public interface LoadSettlementForTaxPort {

    Optional<TaxSettlementView> findById(Long settlementId);
}
