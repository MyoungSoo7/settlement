package github.lms.lemuel.tax.domain.scan;

import java.util.Objects;

/**
 * 사업자등록번호 값 객체 — OCR 로 읽어낸 <b>신뢰할 수 없는 입력</b>을 담는다.
 *
 * <p>스캔본에서 뽑은 값은 오인식이 정상 범주이므로 <b>생성은 실패하지 않는다</b>. 대신 국세청 검증식
 * (가중치 1·3·7·1·3·7·1·3·5 + 9번째 자리 ×5 의 십의 자리 보정)으로 {@link #isValid()} 판정만 내리고,
 * 리뷰 필요 여부는 상위({@link ExtractedTaxInvoice#needsReview})가 결정한다.
 *
 * <p>PII: {@link #toString()}·{@link #masked()} 는 뒤 5자리를 가린다 — 로그·에러 메시지로 원문이 새지
 * 않게 하려는 것이다. 원문이 필요한 저장 경로만 {@link #digits()} 를 쓴다(영속 계층에서 앱단 암호화).
 */
public final class BusinessRegistrationNumber {

    private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};
    private static final int LENGTH = 10;
    private static final String ABSENT_MASK = "-";
    private static final String UNKNOWN_MASK = "*****";

    /** 숫자만 남긴 정규화 값. 인식 실패(숫자 0개)면 null. */
    private final String digits;

    private BusinessRegistrationNumber(String digits) {
        this.digits = digits;
    }

    /** 하이픈·공백 등 구분자를 제거해 만든다. null·공백·숫자 없음은 "미존재"로 다룬다. */
    public static BusinessRegistrationNumber of(String raw) {
        if (raw == null) {
            return new BusinessRegistrationNumber(null);
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return new BusinessRegistrationNumber(sb.isEmpty() ? null : sb.toString());
    }

    public boolean isPresent() {
        return digits != null;
    }

    /** 10자리 + 국세청 체크섬을 모두 만족하는가. 미존재는 무효다. */
    public boolean isValid() {
        if (digits == null || digits.length() != LENGTH) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += digitAt(i) * WEIGHTS[i];
        }
        sum += (digitAt(8) * 5) / 10;            // 9번째 자리 ×5 의 십의 자리 보정
        int check = (10 - (sum % 10)) % 10;
        return check == digitAt(9);
    }

    /** 정규화된 숫자열(영속·암호화 대상). 미존재면 null. */
    public String digits() {
        return digits;
    }

    /** 표시·로그용 마스킹 — 뒤 5자리는 노출하지 않는다. */
    public String masked() {
        if (digits == null) {
            return ABSENT_MASK;
        }
        if (digits.length() != LENGTH) {
            return UNKNOWN_MASK;   // 자릿수가 어긋난 오인식 값은 원문을 흘리지 않는다
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + UNKNOWN_MASK;
    }

    private int digitAt(int index) {
        return digits.charAt(index) - '0';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof BusinessRegistrationNumber other && Objects.equals(digits, other.digits);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(digits);
    }

    @Override
    public String toString() {
        return masked();
    }
}
