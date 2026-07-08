package github.lms.lemuel.company.application.port.out;

import java.util.List;

public interface LoadSellerLinkPort {

    /** 해당 기업(종목코드)에 링크된 셀러 ID 목록 — 평판 이벤트 enrichment 에 동봉된다. */
    List<Long> sellersOf(String stockCode);
}
