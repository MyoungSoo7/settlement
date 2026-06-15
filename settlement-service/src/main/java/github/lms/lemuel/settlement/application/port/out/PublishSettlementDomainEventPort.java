package github.lms.lemuel.settlement.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 정산 도메인 이벤트를 외부(loan-service)로 발행하는 아웃바운드 포트 (Transactional Outbox 경유).
 *
 * <p>Outbox 폴러가 aggregateType="Settlement" + eventType 으로 토픽을 자동 라우팅한다:
 * <ul>
 *   <li>SettlementCreated   → lemuel.settlement.created   (loan: 로컬 정산뷰 적재 = 담보)</li>
 *   <li>SettlementConfirmed → lemuel.settlement.confirmed (loan: 상환 차감 트리거)</li>
 * </ul>
 *
 * <p>기존 {@code PublishSettlementEventPort}(인프로세스 ES 색인 이벤트)와 별개의 Kafka 발행 경로다.
 */
public interface PublishSettlementDomainEventPort {

    void publishSettlementCreated(long settlementId, long sellerId, BigDecimal amount, LocalDate dueDate);

    void publishSettlementConfirmed(long settlementId, long sellerId, BigDecimal amount);
}
