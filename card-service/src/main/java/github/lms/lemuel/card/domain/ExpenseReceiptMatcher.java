package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 매입 ↔ 영수증 대사 규칙 (순수 도메인 — 포트·DB 없이 판정만 한다. tax 의 {@code TaxInvoiceScanMatcher} 와 같은 결).
 *
 * <p>판정 순서가 곧 정책이다:
 * <ol>
 *   <li><b>신뢰도 미달 → NEEDS_REVIEW</b> — 믿을 수 없는 값으로 불일치를 선고하면 멀쩡한 영수증이
 *       종결(MISMATCHED)로 떨어진다. 사람 리뷰가 먼저다.</li>
 *   <li><b>총액 → compareTo 정확 일치</b> — 1원 차이도 불일치. 허용 오차를 두는 순간 그 오차만큼의
 *       증빙 없는 지출이 통과한다.</li>
 *   <li><b>거래일 → 매입일(KST) ±1일</b> — VAN 매입 시점과 전표 시점의 하루 차를 흡수한다.
 *       판독 불가(null)는 불일치가 아니라 리뷰다.</li>
 * </ol>
 *
 * <p>상호명은 판정에 쓰지 않는다 — OCR 상호 표기("김밥천국 강남점")는 가맹점 등록명과 상시 불일치한다.
 */
public final class ExpenseReceiptMatcher {

    /** 매입 시각의 거래일 환산 기준 — 국내 VAN 매입은 KST 로 전표가 찍힌다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long DATE_TOLERANCE_DAYS = 1;

    private ExpenseReceiptMatcher() {
    }

    /**
     * 영수증 추출값과 매입을 대조해 도달할 상태를 정한다.
     *
     * @param extracted       OCR 추출 결과
     * @param capturedAmount  매입 금액 ({@code CardCapture.capturedAmount})
     * @param capturedAt      매입 시각
     * @param reviewThreshold 신뢰도 리뷰 임계 (미만이면 NEEDS_REVIEW)
     */
    public static ReceiptMatchDecision decide(ExtractedReceipt extracted, BigDecimal capturedAmount,
                                              Instant capturedAt, BigDecimal reviewThreshold) {
        if (extracted == null || capturedAmount == null || capturedAt == null || reviewThreshold == null) {
            throw new IllegalArgumentException("대사 입력은 전부 필수입니다");
        }
        if (extracted.confidence().compareTo(reviewThreshold) < 0) {
            return ReceiptMatchDecision.needsReview("판독 신뢰도 미달: "
                    + extracted.confidence().toPlainString() + " < " + reviewThreshold.toPlainString());
        }
        if (extracted.totalAmount().compareTo(capturedAmount) != 0) {
            return ReceiptMatchDecision.mismatched("총액 불일치: 영수증 "
                    + extracted.totalAmount().toPlainString() + " vs 매입 " + capturedAmount.toPlainString());
        }
        if (extracted.transactionDate() == null) {
            return ReceiptMatchDecision.needsReview("거래일 판독 불가 — 육안 대조 필요");
        }
        LocalDate capturedDate = capturedAt.atZone(KST).toLocalDate();
        long dayDiff = Math.abs(ChronoUnit.DAYS.between(capturedDate, extracted.transactionDate()));
        if (dayDiff > DATE_TOLERANCE_DAYS) {
            return ReceiptMatchDecision.mismatched("거래일 불일치: 영수증 "
                    + extracted.transactionDate() + " vs 매입일(KST) " + capturedDate);
        }
        return ReceiptMatchDecision.matched();
    }
}
