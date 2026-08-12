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

    /**
     * 가변 반복부의 건수 필드 값 — 비어 있으면 실제 건수로 채우고, 값이 있으면 <b>실제 건수와 일치</b>해야 한다.
     *
     * <p>건수 필드와 명세 건수가 어긋난 전문은 은행 대사에서 통째로 반송되거나, 더 나쁘게는 앞의 n건만
     * 처리되고 나머지가 조용히 누락된다. 보내기 전에 막는다.
     */
    public static String count(String declared, int actual, String fieldName) {
        String trimmed = declared == null ? "" : declared.trim();
        if (trimmed.isEmpty()) return String.valueOf(actual);
        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new FepProtocolException("건수 필드가 숫자가 아니다: " + fieldName + "='" + declared + "'", e);
        }
        if (value != actual) {
            throw new FepProtocolException(
                    "건수 필드와 실제 명세 건수가 다르다: " + fieldName + "=" + value + " != " + actual + "건");
        }
        return String.valueOf(actual);
    }

    /** {@code null} 을 빈 문자열로 — 레이아웃이 규격대로 패딩한다. */
    public static String text(String value) {
        return value == null ? "" : value;
    }
}
