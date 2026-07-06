package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.ReputationGrade;
import github.lms.lemuel.company.domain.ReputationScore;

/**
 * 평판 등급 변동 이벤트 발행 포트 (ADR 0023 Phase 3).
 *
 * <p>구현체는 Transactional Outbox 에 기록하고, shared-common 폴러가 Kafka
 * (lemuel.company.reputation_changed) 로 비동기 발행한다 — loan 이 리스크 프로젝션으로 소비한다.
 */
public interface PublishReputationEventPort {

    /**
     * @param score        새로 저장된 스냅샷
     * @param previousGrade 직전 스냅샷 등급 — 최초 스냅샷이면 null
     */
    void publishReputationChanged(ReputationScore score, ReputationGrade previousGrade);
}
