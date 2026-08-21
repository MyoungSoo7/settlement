package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;
import github.lms.lemuel.ledger.application.port.out.LoadSettlementForLedgerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * ledger 가 선언한 {@link LoadSettlementForLedgerPort} 를 settlement 슬라이스가 구현한다 —
 * 정산 요약을 {@link SettlementSummary} 로 넘긴다.
 *
 * <p>이 클래스가 <b>settlement 쪽에 사는 이유</b>: 이전에는 ledger 의 어댑터가 settlement 의
 * JPA 리포지토리·엔티티를 직접 읽었다. 포트를 거치도록 바꾸는 것만으로는 부족하다 —
 * 그래도 ledger→settlement 방향 의존은 남고 순환은 끊기지 않는다. 인터페이스는 필요한 쪽(ledger)이
 * 소유하고 <b>구현은 데이터를 가진 쪽(settlement)이 제공</b>해야 방향이 실제로 뒤집힌다.
 *
 * <p>결과: settlement 의 저장 스키마는 슬라이스 밖으로 새지 않고, 의존은 settlement→ledger
 * 한 방향만 남는다(이미 존재하던 방향이라 새 순환도 생기지 않는다).
 */
@Repository
@RequiredArgsConstructor
public class SettlementForLedgerPersistenceAdapter implements LoadSettlementForLedgerPort {

    private final SpringDataSettlementJpaRepository settlementRepository;

    @Override
    public Optional<SettlementSummary> findById(Long settlementId) {
        return settlementRepository.findById(settlementId).map(this::toSummary);
    }

    private SettlementSummary toSummary(SettlementJpaEntity e) {
        return new SettlementSummary(
                e.getId(),
                e.getPaymentAmount(),
                e.getCommission(),
                e.getNetAmount(),
                e.getSettlementDate(),
                e.getStatus()
        );
    }
}
