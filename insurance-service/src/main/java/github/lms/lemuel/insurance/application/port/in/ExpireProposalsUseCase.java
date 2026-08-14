package github.lms.lemuel.insurance.application.port.in;

import java.time.LocalDate;

/**
 * 가입설계 만기 스윕 배치 — 유효기간(30일) 경과 QUOTED 설계를 EXPIRED 로 전이한다.
 */
public interface ExpireProposalsUseCase {

    /** @return 만기 처리된 설계 수 */
    int expireOn(LocalDate today);
}
