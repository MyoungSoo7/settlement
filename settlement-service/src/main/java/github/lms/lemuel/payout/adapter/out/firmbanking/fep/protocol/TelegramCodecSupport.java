package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol;

import java.math.BigDecimal;

/**
 * 생성된 코덱이 쓰는 런타임 보조 — 값 ↔ 전문 문자열 변환. <b>손으로 쓴 코드</b>다(생성물 아님).
 *
 * <p>전문의 N 필드는 부호를 표현할 수단이 없다(좌측 0 패딩 고정길이). 음수 금액을 넣으면 부호가
 * 조용히 사라져 <b>반대 방향 이체</b>가 되므로 인코딩 단계에서 거부한다.
 */
public final class TelegramCodecSupport {

    private TelegramCodecSupport() {
    }

    /** 금액 → 전문 숫자열. {@code null} 은 빈 값(레이아웃이 0 패딩)으로 흘린다. */
    public static String digits(BigDecimal value, int scale, String fieldName) {
        if (value == null) return "";
        if (value.signum() < 0) {
            throw new FepProtocolException("음수 금액은 전문으로 표현할 수 없다: " + fieldName + "=" + value.toPlainString());
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(scale, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new FepProtocolException(
                    "금액 소수 자릿수가 규격을 넘는다: " + fieldName + "=" + value.toPlainString() + " (scale=" + scale + ")", e);
        }
        return scaled.unscaledValue().toString();
    }

    /** 전문 숫자열 → 금액. 공백(미사용 슬롯)은 {@code null}. */
    public static BigDecimal decimal(String raw, int scale, String fieldName) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return new BigDecimal(trimmed).movePointLeft(scale);
        } catch (NumberFormatException e) {
            throw new FepProtocolException("숫자가 아닌 금액 필드 수신: " + fieldName + "='" + raw + "'", e);
        }
    }

    /** {@code null} 을 빈 문자열로 — 레이아웃이 규격대로 패딩한다. */
    public static String text(String value) {
        return value == null ? "" : value;
    }
}
