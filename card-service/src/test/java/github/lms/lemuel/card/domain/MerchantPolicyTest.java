package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MerchantPolicy#evaluate(String, BigDecimal, boolean, boolean, BigDecimal, BigDecimal)} 단위 테스트.
 *
 * <p>모든 거절 분기를 독립적으로 검증한다:
 * <ol>
 *   <li>차단 MCC(blockedMccs) 명중</li>
 *   <li>허용 MCC 목록 외 MCC(allowedMccs, 목록에 없는 경우)</li>
 *   <li>허용 MCC 목록 외 MCC(mcc = null, 목록 비어있지 않은 경우)</li>
 *   <li>해외 거래 불허</li>
 *   <li>온라인 거래 불허</li>
 *   <li>1회 한도 초과(maxPerTransactionAmount)</li>
 *   <li>일 한도 초과(dailySpendLimit + 누적)</li>
 *   <li>월 한도 초과(monthlySpendLimit + 누적)</li>
 * </ol>
 * 통과 케이스: 모든 조건 충족 시 Optional.empty() 반환
 */
class MerchantPolicyTest {

    private static final BigDecimal AMT_1K = new BigDecimal("1000");
    private static final BigDecimal AMT_10K = new BigDecimal("10000");
    private static final BigDecimal AMT_100K = new BigDecimal("100000");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * 제약 없는 기본 정책 빌더.
     * 각 테스트 케이스에서 overrides 를 통해 특정 제약만 설정한다.
     */
    private MerchantPolicy noRestrictions() {
        return MerchantPolicy.builder()
                .cardAccountId(1L)
                .overseasEnabled(true)
                .onlineEnabled(true)
                .build();
    }

    @Nested
    @DisplayName("MCC 필터링")
    class MccFiltering {

        @Test
        @DisplayName("blockedMccs 에 포함된 MCC → MERCHANT_POLICY_VIOLATION")
        void blocked_mcc_is_declined() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .blockedMccs(Set.of("5813", "5814")) // 주류·담배
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5813", AMT_1K, false, false, ZERO, ZERO);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }

        @Test
        @DisplayName("차단 목록에 없는 MCC → 통과")
        void non_blocked_mcc_passes() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .blockedMccs(Set.of("5813"))
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_1K, false, false, ZERO, ZERO);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("allowedMccs 에 없는 MCC → MERCHANT_POLICY_VIOLATION")
        void mcc_not_in_allowed_list_is_declined() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .allowedMccs(Set.of("5812", "5999")) // 식당·기타 소매
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("7011", AMT_1K, false, false, ZERO, ZERO); // 호텔

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }

        @Test
        @DisplayName("allowedMccs 목록이 있을 때 mcc=null → MERCHANT_POLICY_VIOLATION")
        void null_mcc_with_allowedList_is_declined() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .allowedMccs(Set.of("5812"))
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate(null, AMT_1K, false, false, ZERO, ZERO);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }

        @Test
        @DisplayName("allowedMccs 비어있고 blockedMccs 없으면 모든 MCC 통과")
        void empty_allowed_and_blocked_permits_any_mcc() {
            MerchantPolicy policy = noRestrictions();

            Optional<DeclineReason> result = policy.evaluate("9999", AMT_1K, false, false, ZERO, ZERO);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("allowedMccs 에 포함된 MCC → 통과")
        void mcc_in_allowed_list_passes() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .allowedMccs(Set.of("5812", "5999"))
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_1K, false, false, ZERO, ZERO);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("차단 MCC 와 허용 MCC 모두 설정 — 차단 MCC 우선 적용")
        void blocked_takes_priority_over_allowed() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .blockedMccs(Set.of("5812"))
                    .allowedMccs(Set.of("5812", "5999")) // 허용 목록에도 있지만 차단이 우선
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_1K, false, false, ZERO, ZERO);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
    }

    @Nested
    @DisplayName("거래 채널 제한")
    class ChannelRestriction {

        @Test
        @DisplayName("overseas=true 이고 overseasEnabled=false → MERCHANT_POLICY_VIOLATION")
        void overseas_disabled_declines_overseas_tx() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .overseasEnabled(false)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_1K, true, false, ZERO, ZERO);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }

        @Test
        @DisplayName("online=true 이고 onlineEnabled=false → MERCHANT_POLICY_VIOLATION")
        void online_disabled_declines_online_tx() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .overseasEnabled(true)
                    .onlineEnabled(false)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_1K, false, true, ZERO, ZERO);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }

        @Test
        @DisplayName("overseas=false 이면 overseasEnabled=false 여도 통과")
        void domestic_tx_passes_even_when_overseas_disabled() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .overseasEnabled(false)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_1K, false, false, ZERO, ZERO);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("online=false 이면 onlineEnabled=false 여도 통과")
        void offline_tx_passes_even_when_online_disabled() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .overseasEnabled(true)
                    .onlineEnabled(false)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_1K, false, false, ZERO, ZERO);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("금액 한도")
    class AmountLimits {

        @Test
        @DisplayName("amount > maxPerTransactionAmount → MERCHANT_POLICY_VIOLATION")
        void per_tx_limit_exceeded_is_declined() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .maxPerTransactionAmount(new BigDecimal("5000"))
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_10K, false, false, ZERO, ZERO);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }

        @Test
        @DisplayName("amount == maxPerTransactionAmount → 통과(경계값)")
        void per_tx_at_limit_passes() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .maxPerTransactionAmount(AMT_10K)
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_10K, false, false, ZERO, ZERO);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("todaySpend + amount > dailySpendLimit → MERCHANT_POLICY_VIOLATION")
        void daily_limit_exceeded_with_cumulative_spend_is_declined() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .dailySpendLimit(AMT_100K)
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            // 이미 95,000 원 썼고 10,000 원을 더 쓰려 함 = 105,000 > 100,000
            BigDecimal todaySpend = new BigDecimal("95000");
            Optional<DeclineReason> result = policy.evaluate("5812", AMT_10K, false, false, todaySpend, ZERO);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }

        @Test
        @DisplayName("todaySpend + amount == dailySpendLimit → 통과(경계값)")
        void daily_limit_exactly_at_limit_passes() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .dailySpendLimit(AMT_100K)
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            BigDecimal todaySpend = new BigDecimal("90000");
            Optional<DeclineReason> result = policy.evaluate("5812", AMT_10K, false, false, todaySpend, ZERO);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("monthlySpend + amount > monthlySpendLimit → MERCHANT_POLICY_VIOLATION")
        void monthly_limit_exceeded_is_declined() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .monthlySpendLimit(new BigDecimal("500000"))
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            BigDecimal monthlySpend = new BigDecimal("495000");
            Optional<DeclineReason> result = policy.evaluate("5812", AMT_10K, false, false, ZERO, monthlySpend);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }

        @Test
        @DisplayName("todaySpend=null → 0 으로 처리 (NPE 없음)")
        void null_today_spend_treated_as_zero() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .dailySpendLimit(AMT_100K)
                    .overseasEnabled(true)
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_10K, false, false, null, null);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("복합 조건 및 통과 케이스")
    class Combined {

        @Test
        @DisplayName("모든 제약 없을 때 → 통과")
        void no_restrictions_passes() {
            MerchantPolicy policy = noRestrictions();

            Optional<DeclineReason> result = policy.evaluate("5812", AMT_10K, true, true, ZERO, ZERO);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("모든 제약 충족 시 → 통과")
        void all_constraints_satisfied_passes() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .allowedMccs(Set.of("5812"))
                    .maxPerTransactionAmount(new BigDecimal("50000"))
                    .dailySpendLimit(AMT_100K)
                    .monthlySpendLimit(new BigDecimal("1000000"))
                    .overseasEnabled(false)    // 해외 불허 — 이 테스트는 국내
                    .onlineEnabled(true)
                    .build();

            Optional<DeclineReason> result = policy.evaluate(
                    "5812", new BigDecimal("30000"), false, false,
                    new BigDecimal("50000"), new BigDecimal("200000")
            );

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("blockedMccs 와 overseas 모두 위반 — 첫 번째 위반(blocked MCC)이 반환된다")
        void multiple_violations_first_evaluated_is_returned() {
            MerchantPolicy policy = MerchantPolicy.builder()
                    .cardAccountId(1L)
                    .blockedMccs(Set.of("5813"))
                    .overseasEnabled(false)
                    .onlineEnabled(true)
                    .build();

            // MCC 차단이 먼저 평가됨
            Optional<DeclineReason> result = policy.evaluate("5813", AMT_1K, true, false, ZERO, ZERO);

            assertThat(result).contains(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
    }
}
