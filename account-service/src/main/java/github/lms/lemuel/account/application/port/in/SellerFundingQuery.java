package github.lms.lemuel.account.application.port.in;

import java.math.BigDecimal;

/**
 * 셀러 재원 조회 계약 — card-service 가 법인카드 한도를 산정할 때 쓰는 입력.
 *
 * <p>재원 = 확정됐지만 아직 셀러에게 나가지 않은 정산금 + 홀드백 유보분
 *        = SELLER_PAYABLE 잔액 + HOLDBACK_PAYABLE 잔액.
 * 이 등식이 성립하는 이유는 account 가 settlement 의 confirmed/payout/holdback_consumed/
 * recovery_offset/withholding 을 모두 소비해 두 계정에 반영하기 때문이다.
 */
public interface SellerFundingQuery {

    SellerFunding sellerFunding(String sellerId);

    record SellerFunding(String sellerId, BigDecimal sellerPayable, BigDecimal holdbackPayable) {
    }
}
