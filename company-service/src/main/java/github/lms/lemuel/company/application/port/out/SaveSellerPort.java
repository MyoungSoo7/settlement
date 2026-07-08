package github.lms.lemuel.company.application.port.out;

/**
 * 셀러(회원) 등록 정보를 적재하는 포트 (user.registered 수신 → 링크 대상 목록).
 * user.registered 페이로드에는 기업 연결 키가 없어(userId/email 만) 자동 매핑은 불가능 —
 * 실제 셀러↔기업 링크는 admin 명시 링크로 맺는다(ADR 0023 Phase 3 후속).
 */
public interface SaveSellerPort {

    /** sellerId 멱등 UPSERT. */
    void record(Long sellerId, String email);
}
