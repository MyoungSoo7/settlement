package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;

import java.util.Optional;

/**
 * Settlement 도메인을 ledger 가 직접 참조하지 않도록 분리한 read-only 포트.
 *
 * <p>구현체는 {@code SettlementJpaEntity} 를 어댑터 레벨에서만 읽고
 * {@link SettlementSummary} DTO 로 매핑해 반환한다.
 */
public interface LoadSettlementForLedgerPort {

    Optional<SettlementSummary> findById(Long settlementId);
}
