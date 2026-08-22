package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 영수증 OCR 추출 결과 VO (순수 도메인, 불변).
 *
 * <p>총액은 필수 — 총액을 못 읽은 추출은 대사 근거가 될 수 없어 어댑터가 503 으로 끊는다
 * (ADR 0036 무폴백). 상호명·거래일은 판독 실패를 {@code null} 로 표현한다 — 지어내지 않고,
 * 거래일 null 은 대사에서 NEEDS_REVIEW 로 흐른다.
 *
 * <p><b>신뢰도는 필드마다 따로 갖는다.</b> 하나로 합쳐 두면 쉬운 필드의 확신이 어려운 필드의
 * 불확실성을 덮는다 — 실측에서 비전 모델이 반사광에 덮인 거래일을 6년이나 틀리게 읽고도
 * 신뢰도 0.98 을 붙인 적이 있다. 총액을 또렷하게 읽었다는 이유였다. 그 값이 임계를 넘어
 * 멀쩡한 영수증이 {@code MISMATCHED} 로 종결됐다(재첨부 외엔 되돌릴 수 없는 상태다).
 *
 * <p>그래서 여기에는 "전체 신뢰도" 가 없다. 판정은 {@link ExpenseReceiptMatcher} 가 필드마다
 * 자기 신뢰도로 게이트한다.
 *
 * @param merchantName     영수증 상호명 (판독 실패 null, 공백은 null 정규화) — 대사 판정에 쓰지 않는 참고 정보
 * @param transactionDate  거래일 (판독 실패 null)
 * @param totalAmount      총액 (필수·양수, BigDecimal 강제)
 * @param amountConfidence 총액 판독 신뢰도 0~1 (필수 — 총액이 항상 있으므로 신뢰도도 항상 있다)
 * @param dateConfidence   거래일 판독 신뢰도 0~1. <b>거래일이 없으면 반드시 null, 있으면 반드시 non-null</b>
 *                         — 없는 필드에 신뢰도를 붙이거나 있는 필드의 신뢰도를 빠뜨리면 판정이 흔들린다
 */
public record ExtractedReceipt(String merchantName, LocalDate transactionDate,
                               BigDecimal totalAmount, BigDecimal amountConfidence,
                               BigDecimal dateConfidence) {

    public ExtractedReceipt {
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("영수증 총액은 양수여야 합니다: " + totalAmount);
        }
        requireConfidence(amountConfidence, "총액 판독 신뢰도");
        if (transactionDate == null && dateConfidence != null) {
            throw new IllegalArgumentException(
                    "거래일을 못 읽었는데 거래일 신뢰도가 있습니다: " + dateConfidence);
        }
        if (transactionDate != null) {
            requireConfidence(dateConfidence, "거래일 판독 신뢰도");
        }
        merchantName = normalize(merchantName);
    }

    /**
     * 가장 못 믿는 필드의 신뢰도 — <b>화면 표시용이다. 대사 판정에 쓰지 말 것.</b>
     *
     * <p>판정에 쓰는 순간 이 타입이 없애려던 결함(쉬운 필드가 어려운 필드를 덮는 것)이 되돌아온다.
     * 리뷰 큐 화면처럼 "이 영수증을 얼마나 믿을 수 있나" 를 한 숫자로 보여줘야 할 때만 쓴다.
     */
    public BigDecimal weakestConfidence() {
        if (dateConfidence == null) {
            return amountConfidence;
        }
        return amountConfidence.min(dateConfidence);
    }

    private static void requireConfidence(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(label + "는 0~1 이어야 합니다: " + value);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
