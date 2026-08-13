package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매입 ↔ 영수증 대사 규칙 (순수 도메인 — 포트·DB 없이 판정만).
 *
 * <p>판정 순서: ① 신뢰도 미달 → NEEDS_REVIEW (값 자체를 믿을 수 없으니 불일치 선고보다 사람 리뷰가 먼저)
 * ② 총액 불일치 → MISMATCHED ③ 거래일 판독 불가 → NEEDS_REVIEW / ±1일 초과 → MISMATCHED ④ 전부 통과 → MATCHED.
 * 상호명은 참고 정보일 뿐 판정에 쓰지 않는다 — OCR 상호 표기는 등록 상호와 상시 불일치한다.
 */
class ExpenseReceiptMatcherTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.80");
    /** 매입 시각: 2026-08-10 12:00 KST (03:00 UTC) */
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-10T03:00:00Z");
    private static final BigDecimal CAPTURED_AMOUNT = new BigDecimal("12000");

    private static ExtractedReceipt receipt(String amount, LocalDate date, String confidence) {
        return new ExtractedReceipt("김밥천국", date, new BigDecimal(amount), new BigDecimal(confidence));
    }

    @Test
    @DisplayName("총액·거래일 일치 + 신뢰도 충족이면 MATCHED")
    void matched() {
        ReceiptMatchDecision decision = ExpenseReceiptMatcher.decide(
                receipt("12000", LocalDate.of(2026, 8, 10), "0.93"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(ExpenseReceiptStatus.MATCHED);
        assertThat(decision.note()).isNull();
    }

    @Test
    @DisplayName("신뢰도 미달이면 값이 일치해도 NEEDS_REVIEW — 임계 동률(0.80)은 통과")
    void confidenceBelowThresholdNeedsReview() {
        ReceiptMatchDecision below = ExpenseReceiptMatcher.decide(
                receipt("12000", LocalDate.of(2026, 8, 10), "0.79"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD);
        ReceiptMatchDecision atThreshold = ExpenseReceiptMatcher.decide(
                receipt("12000", LocalDate.of(2026, 8, 10), "0.80"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD);

        assertThat(below.status()).isEqualTo(ExpenseReceiptStatus.NEEDS_REVIEW);
        assertThat(below.note()).contains("신뢰도");
        assertThat(atThreshold.status()).isEqualTo(ExpenseReceiptStatus.MATCHED);
    }

    @Test
    @DisplayName("총액 불일치는 MISMATCHED — 1원 차이도 불일치 (compareTo 정확 일치)")
    void amountMismatch() {
        ReceiptMatchDecision decision = ExpenseReceiptMatcher.decide(
                receipt("12001", LocalDate.of(2026, 8, 10), "0.93"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(ExpenseReceiptStatus.MISMATCHED);
        assertThat(decision.note()).contains("총액").contains("12001").contains("12000");
    }

    @Test
    @DisplayName("scale 이 달라도 값이 같으면 일치 (12000 vs 12000.00)")
    void amountScaleInsensitive() {
        ReceiptMatchDecision decision = ExpenseReceiptMatcher.decide(
                receipt("12000.00", LocalDate.of(2026, 8, 10), "0.93"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(ExpenseReceiptStatus.MATCHED);
    }

    @Test
    @DisplayName("거래일은 매입일(KST) ±1일까지 허용 — 2일 차이는 MISMATCHED")
    void dateTolerance() {
        assertThat(ExpenseReceiptMatcher.decide(
                receipt("12000", LocalDate.of(2026, 8, 9), "0.93"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD)
                .status()).isEqualTo(ExpenseReceiptStatus.MATCHED);
        assertThat(ExpenseReceiptMatcher.decide(
                receipt("12000", LocalDate.of(2026, 8, 11), "0.93"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD)
                .status()).isEqualTo(ExpenseReceiptStatus.MATCHED);

        ReceiptMatchDecision twoDays = ExpenseReceiptMatcher.decide(
                receipt("12000", LocalDate.of(2026, 8, 8), "0.93"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD);
        assertThat(twoDays.status()).isEqualTo(ExpenseReceiptStatus.MISMATCHED);
        assertThat(twoDays.note()).contains("거래일");
    }

    @Test
    @DisplayName("매입일 판정은 KST 기준 — UTC 15:30(=KST 익일 00:30) 매입과 익일 영수증은 같은 날")
    void capturedDateIsKst() {
        // 2026-08-10T15:30Z = 2026-08-11 00:30 KST → 매입일은 8/11
        Instant lateNightUtc = Instant.parse("2026-08-10T15:30:00Z");

        ReceiptMatchDecision decision = ExpenseReceiptMatcher.decide(
                receipt("12000", LocalDate.of(2026, 8, 12), "0.93"), CAPTURED_AMOUNT, lateNightUtc, THRESHOLD);

        assertThat(decision.status()).isEqualTo(ExpenseReceiptStatus.MATCHED);   // 8/11 ↔ 8/12 = 1일 차
    }

    @Test
    @DisplayName("거래일 판독 불가(null)는 불일치가 아니라 NEEDS_REVIEW")
    void unreadableDateNeedsReview() {
        ReceiptMatchDecision decision = ExpenseReceiptMatcher.decide(
                new ExtractedReceipt("김밥천국", null, new BigDecimal("12000"), new BigDecimal("0.93")),
                CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(ExpenseReceiptStatus.NEEDS_REVIEW);
        assertThat(decision.note()).contains("거래일");
    }

    @Test
    @DisplayName("신뢰도 미달이 총액 불일치보다 먼저다 — 믿을 수 없는 값으로 불일치를 선고하지 않는다")
    void confidenceCheckPrecedesAmount() {
        ReceiptMatchDecision decision = ExpenseReceiptMatcher.decide(
                receipt("99999", LocalDate.of(2026, 8, 10), "0.30"), CAPTURED_AMOUNT, CAPTURED_AT, THRESHOLD);

        assertThat(decision.status()).isEqualTo(ExpenseReceiptStatus.NEEDS_REVIEW);
    }
}
