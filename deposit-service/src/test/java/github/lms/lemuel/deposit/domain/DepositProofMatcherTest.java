package github.lms.lemuel.deposit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수기 기표 요청 ↔ 예치금 증빙 대사 규칙 (순수 도메인, 지연 대사).
 *
 * <p>판정 순서: 신뢰도 → 이체금액(정확 일치) → 이체일(기표일 ±허용일수, null 은 리뷰) → MATCHED.
 * 입금자명은 판정 불사용.
 */
class DepositProofMatcherTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.80");
    private static final LocalDate ENTRY_DATE = LocalDate.of(2026, 8, 14);
    private static final BigDecimal AMOUNT = new BigDecimal("3000000.00");
    private static final int TOLERANCE = 3;

    private static ExtractedTransferProof proof(String amount, LocalDate date, String confidence) {
        return new ExtractedTransferProof("홍길동", date, new BigDecimal(amount), new BigDecimal(confidence));
    }

    @Test
    @DisplayName("금액·이체일 일치 + 신뢰도 충족이면 MATCHED — scale 차이는 일치")
    void matched() {
        DepositProofMatchDecision decision = DepositProofMatcher.decide(
                proof("3000000", LocalDate.of(2026, 8, 13), "0.93"), AMOUNT, ENTRY_DATE, TOLERANCE, THRESHOLD);

        assertThat(decision.status()).isEqualTo(DepositProofStatus.MATCHED);
        assertThat(decision.note()).isNull();
    }

    @Test
    @DisplayName("신뢰도 미달이면 값이 일치해도 NEEDS_REVIEW — 임계 동률(0.80)은 통과")
    void confidenceThreshold() {
        assertThat(DepositProofMatcher.decide(
                proof("3000000", ENTRY_DATE, "0.79"), AMOUNT, ENTRY_DATE, TOLERANCE, THRESHOLD).status())
                .isEqualTo(DepositProofStatus.NEEDS_REVIEW);
        assertThat(DepositProofMatcher.decide(
                proof("3000000", ENTRY_DATE, "0.80"), AMOUNT, ENTRY_DATE, TOLERANCE, THRESHOLD).status())
                .isEqualTo(DepositProofStatus.MATCHED);
    }

    @Test
    @DisplayName("이체금액 불일치는 MISMATCHED — 1원 차이도 불일치 (잔고 단일 진실원)")
    void amountExactMatch() {
        DepositProofMatchDecision decision = DepositProofMatcher.decide(
                proof("3000001", ENTRY_DATE, "0.93"), AMOUNT, ENTRY_DATE, TOLERANCE, THRESHOLD);

        assertThat(decision.status()).isEqualTo(DepositProofStatus.MISMATCHED);
        assertThat(decision.note()).contains("이체금액");
    }

    @Test
    @DisplayName("이체일은 기표일 ±허용일수(기본 3일) — 수기 기표 리드타임 흡수, 초과는 MISMATCHED")
    void dateToleranceAbsorbsManualLeadTime() {
        // 3일 전 이체 → 오늘 기표: 정상 업무 흐름
        assertThat(DepositProofMatcher.decide(
                proof("3000000", LocalDate.of(2026, 8, 11), "0.93"), AMOUNT, ENTRY_DATE, TOLERANCE, THRESHOLD)
                .status()).isEqualTo(DepositProofStatus.MATCHED);

        DepositProofMatchDecision fourDays = DepositProofMatcher.decide(
                proof("3000000", LocalDate.of(2026, 8, 10), "0.93"), AMOUNT, ENTRY_DATE, TOLERANCE, THRESHOLD);
        assertThat(fourDays.status()).isEqualTo(DepositProofStatus.MISMATCHED);
        assertThat(fourDays.note()).contains("이체일");
    }

    @Test
    @DisplayName("이체일 판독 불가(null)는 불일치가 아니라 NEEDS_REVIEW")
    void unreadableDateNeedsReview() {
        DepositProofMatchDecision decision = DepositProofMatcher.decide(
                proof("3000000", null, "0.93"), AMOUNT, ENTRY_DATE, TOLERANCE, THRESHOLD);

        assertThat(decision.status()).isEqualTo(DepositProofStatus.NEEDS_REVIEW);
        assertThat(decision.note()).contains("이체일");
    }

    @Test
    @DisplayName("신뢰도 미달이 금액 불일치보다 먼저다 — 믿을 수 없는 값으로 불일치를 선고하지 않는다")
    void confidencePrecedesAmount() {
        assertThat(DepositProofMatcher.decide(
                proof("9999999", ENTRY_DATE, "0.30"), AMOUNT, ENTRY_DATE, TOLERANCE, THRESHOLD).status())
                .isEqualTo(DepositProofStatus.NEEDS_REVIEW);
    }
}
