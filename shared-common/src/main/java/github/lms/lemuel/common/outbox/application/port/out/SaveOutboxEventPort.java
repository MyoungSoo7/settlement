package github.lms.lemuel.common.outbox.application.port.out;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

/**
 * 도메인 서비스가 동일 @Transactional 안에서 outbox 레코드를 기록하기 위한 포트.
 */
public interface SaveOutboxEventPort {
    OutboxEvent save(OutboxEvent event);
}
