package github.lms.lemuel.investment.application.port.out;

import java.math.BigDecimal;

/** 재원 프로젝션 조회 아웃바운드 포트. */
public interface LoadFundingViewPort {

    /** 셀러의 확정(CONFIRMED) 정산금 합계. */
    BigDecimal sumConfirmedBySeller(long sellerId);
}
