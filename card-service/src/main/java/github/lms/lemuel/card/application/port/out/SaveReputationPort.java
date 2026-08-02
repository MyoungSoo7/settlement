package github.lms.lemuel.card.application.port.out;

/**
 * 평판 프로젝션 적재 포트 — {@code CompanyReputationChangedConsumer} 전용.
 */
public interface SaveReputationPort {

    /**
     * 셀러의 평판 등급을 멱등 UPSERT 한다(재수신 시 최신 등급으로 덮어씀).
     *
     * @param sellerId 문자열 셀러 식별자 — {@code company.reputation_changed} 이벤트의
     *                 {@code sellerIds}(정수 배열) 원소 하나를 {@code String.valueOf()} 로 변환한 값.
     * @param grade    "A".."E" — {@code ReputationGrade.name()} 과 동일한 문자열 표현.
     */
    void upsertGrade(String sellerId, String grade);
}
