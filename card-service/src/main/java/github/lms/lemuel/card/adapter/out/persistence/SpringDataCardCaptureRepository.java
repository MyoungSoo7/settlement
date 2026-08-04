package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataCardCaptureRepository
        extends JpaRepository<CardCaptureJpaEntity, Long> {

    /** 자연키(capture_id)로 조회 — 멱등 체크 전용. */
    Optional<CardCaptureJpaEntity> findByCaptureId(String captureId);
}
