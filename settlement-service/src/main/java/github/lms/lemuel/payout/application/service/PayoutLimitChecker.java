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
        return canSend(sellerId, amount, today, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * 같은 배치에서 <b>앞 건들이 이미 쓸 예정인 금액</b>을 함께 반영해 판정한다 — 미리보기 전용.
     *
     * <p>실행 경로는 건별로 송금이 COMPLETED 되면서 DB 합계가 올라가므로 자연히 누적이 반영된다.
     * 미리보기는 아무것도 완료시키지 않아 DB 합계가 고정이라, 같은 셀러의 여러 건이 각각 한도 안이면
     * 전부 "보낼 수 있음"으로 보고된다 — 실제로는 앞 건이 한도를 먹어 뒤 건이 밀리는데도.
     * 미리보기가 실제보다 많이 나간다고 약속하면 운영자의 자금 계획이 어긋나므로, 예정액을 함께 센다.
     *
     * @param projectedSellerAmount 이 배치에서 해당 셀러 앞 건들의 예정 송금액 합
     * @param projectedSystemAmount 이 배치에서 전체 앞 건들의 예정 송금액 합
     */
    public Decision canSend(Long sellerId, BigDecimal amount, LocalDate today,
                            BigDecimal projectedSellerAmount, BigDecimal projectedSystemAmount) {
        BigDecimal sellerToday = loadPayoutPort.sumCompletedBySellerOn(sellerId, today)
                .add(projectedSellerAmount);
        if (sellerToday.add(amount).compareTo(sellerDailyLimit) > 0) {
            return new Decision(false,
                    "셀러 일 한도 초과: 누적=" + sellerToday + ", 요청=" + amount
                            + ", 한도=" + sellerDailyLimit);
        }

        BigDecimal systemToday = loadPayoutPort.sumCompletedSystemwideOn(today)
                .add(projectedSystemAmount);
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
