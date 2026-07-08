package github.lms.lemuel.company.application.port.out;

public interface SaveSellerLinkPort {

    /** 셀러↔기업(종목코드) 링크 멱등 UPSERT (sellerId 식별자 — 한 셀러는 한 기업에 링크). */
    void link(Long sellerId, String stockCode);
}
