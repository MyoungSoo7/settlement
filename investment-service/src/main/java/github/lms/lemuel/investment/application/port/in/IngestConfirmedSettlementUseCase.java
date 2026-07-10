package github.lms.lemuel.investment.application.port.in;

import java.math.BigDecimal;

/** settlement 확정 정산금을 재원 프로젝션으로 멱등 적재하는 인바운드 포트(Kafka 컨슈머가 호출). */
public interface IngestConfirmedSettlementUseCase {

    void ingest(IngestConfirmedSettlementCommand command);

    record IngestConfirmedSettlementCommand(Long settlementId, Long sellerId, BigDecimal amount) {
    }
}
