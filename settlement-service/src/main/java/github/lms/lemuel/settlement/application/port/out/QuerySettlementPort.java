package github.lms.lemuel.settlement.application.port.out;

/**
 * 정산 조회 전용 Outbound Port (Read Model)
 * Write model(LoadSettlementPort)과 분리하여 CQRS 원칙 적용
 *
 * <p><b>ISP</b>: 조회 축을 응집 역할 인터페이스로 분리했다 —
 * {@link SettlementSummaryQueryPort}(요약), {@link SettlementSearchQueryPort}(상세 탐색),
 * {@link SettlementReconciliationQueryPort}(대사). 이 포트는 셋을 합성한 편의 집합이며, 한 축만
 * 필요한 소비처는 해당 역할 인터페이스만 의존하면 된다. 어댑터는 이 합성 포트를 구현해 세 역할을
 * 한 번에 만족시킨다.
 */
public interface QuerySettlementPort
        extends SettlementSummaryQueryPort,
                SettlementSearchQueryPort,
                SettlementReconciliationQueryPort {
}
