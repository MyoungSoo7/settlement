package github.lms.lemuel.common.outbox.application.port.out;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

import java.util.List;

/**
 * Outbox 폴러가 PENDING 레코드를 배치 조회하기 위한 포트.
 */
public interface LoadOutboxEventPort {
    /**
     * PENDING 상태의 이벤트를 생성 시간 오름차순으로 최대 limit 개 반환.
     */
    List<OutboxEvent> findPending(int limit);

    /**
     * 운영 지표용 — 현재 PENDING 개수.
     */
    long countPending();
}
