package github.lms.lemuel.insurance.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * 청약 접수 시각 조회 포트.
 *
 * <p>접수 시각(submitted_at)은 도메인이 아니라 DB(DEFAULT NOW())가 소유한다 — 도메인 모델에 없으므로
 * 서류 대사(청약일 ±1일 판정)가 필요로 하는 이 한 값만 별도 포트로 읽는다.
 */
public interface LoadApplicationSubmissionPort {

    Optional<Instant> findSubmittedAt(String applicationId);
}
