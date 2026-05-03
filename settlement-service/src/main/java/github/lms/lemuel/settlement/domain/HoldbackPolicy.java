package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 정산 보류(Holdback) 정책 — 셀러 등급별 보류율 + 보류 기간.
 *
 * <p>보류 정책의 의미: 정산금 일부를 일정 기간 보류했다가 환불·분쟁이 없으면 자동 해제하여
 * 셀러에게 지급. 신뢰도 낮은 셀러의 환불 다발 / 사기 위험을 흡수하는 안전장치.
 *
 * <p>등급별 default:
 * <ul>
 *   <li>NORMAL    : 30% 보류, 30 일</li>
 *   <li>VIP       : 10% 보류, 14 일</li>
 *   <li>STRATEGIC : 0% (즉시 전액 정산)</li>
 * </ul>
 *
 * <p>실 운영에서는 셀러별 분쟁률·환불률을 보고 동적 조정 (FDS 연계).
 */
public record HoldbackPolicy(BigDecimal rate, int releaseDays) {

    public HoldbackPolicy {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("rate 는 0~1");
        }
        if (releaseDays < 0) {
            throw new IllegalArgumentException("releaseDays 는 0 이상");
        }
    }

    /** 등급별 default 정책. */
    public static HoldbackPolicy forTier(SellerTier tier) {
        return switch (tier) {
            case NORMAL    -> new HoldbackPolicy(new BigDecimal("0.30"), 30);
            case VIP       -> new HoldbackPolicy(new BigDecimal("0.10"), 14);
            case STRATEGIC -> new HoldbackPolicy(BigDecimal.ZERO, 0);
        };
    }

    /**
     * 정산일 기준 release 예정일 계산. 영업일 기준으로 N 일 후.
     */
    public LocalDate computeReleaseDate(LocalDate settlementDate) {
        if (releaseDays == 0) return settlementDate;
        return BusinessDayCalculator.addBusinessDays(settlementDate, releaseDays);
    }
}
