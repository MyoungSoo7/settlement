package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.ReputationGrade;

/**
 * 셀러 평판 등급 저장 포트 — (sellerId) 키 멱등 upsert.
 */
public interface SaveReputationPort {

    void upsertGrade(String sellerId, ReputationGrade grade);
}
