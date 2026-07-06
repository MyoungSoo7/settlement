package github.lms.lemuel.loan.application.port.out;

public interface SaveSellerReputationPort {

    /** 셀러별 평판 등급 프로젝션 멱등 UPSERT (sellerId 식별자). */
    void upsert(Long sellerId, String stockCode, int score, String grade);
}
