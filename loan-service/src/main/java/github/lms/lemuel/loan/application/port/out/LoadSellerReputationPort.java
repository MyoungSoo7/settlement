package github.lms.lemuel.loan.application.port.out;

import java.util.Optional;

/**
 * 셀러(법인)의 최신 평판 등급 조회 — 신용 한도 haircut 산정용 (ADR 0023 Phase 3 후속).
 *
 * <p>company 의 평판 이벤트로 적재된 로컬 프로젝션에서 셀러↔기업 링크를 거쳐 등급을 찾는다.
 * 링크/이벤트가 아직 없으면 empty → CreditPolicy 는 haircut 1.0(무변동, fail-open)으로 처리한다.
 */
public interface LoadSellerReputationPort {

    Optional<String> findGrade(Long sellerId);
}
