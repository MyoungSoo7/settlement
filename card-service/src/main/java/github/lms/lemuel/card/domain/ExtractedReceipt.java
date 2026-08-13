package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 영수증 OCR 추출 결과 VO (순수 도메인, 불변).
 *
 * <p>총액·신뢰도는 필수 — 총액을 못 읽은 추출은 대사 근거가 될 수 없어 어댑터가 503 으로 끊는다
 * (ADR 0036 무폴백). 상호명·거래일은 판독 실패를 {@code null} 로 표현한다 — 지어내지 않고,
 * 거래일 null 은 대사에서 NEEDS_REVIEW 로 흐른다.
 *
 * @param merchantName    영수증 상호명 (판독 실패 null, 공백은 null 정규화) — 대사 판정에 쓰지 않는 참고 정보
 * @param transactionDate 거래일 (판독 실패 null)
 * @param totalAmount     총액 (필수·양수, BigDecimal 강제)
 * @param confidence      판독 신뢰도 0~1 (필수)
 */
public record ExtractedReceipt(String merchantName, LocalDate transactionDate,
                               BigDecimal totalAmount, BigDecimal confidence) {

    public ExtractedReceipt {
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("영수증 총액은 양수여야 합니다: " + totalAmount);
        }
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("판독 신뢰도는 0~1 이어야 합니다: " + confidence);
        }
        merchantName = normalize(merchantName);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
