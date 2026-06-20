package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 송금 한도 검사기 — 일별 시스템 한도 + 셀러별 일 한도.
 *
 * <p>실 운영: 셀러 등급별 한도 차등, 시간대별 한도 (야간 한도 별도) 등 정책 추가 가능.
 * 본 구현은 외부 설정 2 개 (시스템·셀러 일 한도) 로 단순화.
 */
@Component
public class PayoutLimitChecker {

    private final LoadPayoutPort loadPayoutPort;
    private final BigDecimal systemDailyLimit;
    private final BigDecimal sellerDailyLimit;

    public PayoutLimitChecker(LoadPayoutPort loadPayoutPort,
                               @Value("${app.payout.system-daily-limit:1000000000}") BigDecimal systemDailyLimit,
                               @Value("${app.payout.seller-daily-limit:100000000}") BigDecimal sellerDailyLimit) {
        this.loadPayoutPort = loadPayoutPort;
        this.systemDailyLimit = systemDailyLimit;
        this.sellerDailyLimit = sellerDailyLimit;
    }

    /**
     * 송금 가능 여부 + 사유. 한도 초과 시 PayoutScheduler 가 다음 영업일로 미룸.
     */
    public Decision canSend(Long sellerId, BigDecimal amount, LocalDate today) {
        BigDecimal sellerToday = loadPayoutPort.sumCompletedBySellerOn(sellerId, today);
        if (sellerToday.add(amount).compareTo(sellerDailyLimit) > 0) {
            return new Decision(false,
                    "셀러 일 한도 초과: 누적=" + sellerToday + ", 요청=" + amount
                            + ", 한도=" + sellerDailyLimit);
        }

        BigDecimal systemToday = loadPayoutPort.sumCompletedSystemwideOn(today);
        if (systemToday.add(amount).compareTo(systemDailyLimit) > 0) {
            return new Decision(false,
                    "시스템 일 한도 초과: 누적=" + systemToday + ", 요청=" + amount
                            + ", 한도=" + systemDailyLimit);
        }

        return new Decision(true, null);
    }

    public record Decision(boolean allowed, String reason) { }

    public BigDecimal getSystemDailyLimit() { return systemDailyLimit; }
    public BigDecimal getSellerDailyLimit() { return sellerDailyLimit; }
}
