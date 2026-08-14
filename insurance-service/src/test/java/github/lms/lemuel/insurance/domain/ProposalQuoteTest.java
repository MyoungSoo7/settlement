package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidProposalException;
import github.lms.lemuel.insurance.domain.exception.InvalidProposalTransitionException;
import github.lms.lemuel.insurance.domain.exception.InvalidSalesChannelException;
import github.lms.lemuel.insurance.domain.exception.ProposalExpiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가입설계 애그리거트 테스트 — 산출 스냅샷 고정, 유효기간 게이트, 전이 합법성.
 */
@DisplayName("ProposalQuote — 가입설계 애그리거트")
class ProposalQuoteTest {

    private static final LocalDate QUOTED_ON = LocalDate.of(2026, 8, 7);
    private static final String APPLICATION_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private static ProposalQuote fcQuote() {
        return ProposalQuote.quote(
                null, "PROD-LIFE-01", "fc-100",
                "홍길동", Gender.M, 37,
                new BigDecimal("100000000"), 20,
                new AppliedRate(11L, new BigDecimal("2.5")),
                SalesChannel.FC, null, QUOTED_ON);
    }

    @Nested
    @DisplayName("산출(quote) — 스냅샷 고정")
    class Quote {

        @Test
        @DisplayName("보험료를 요율로 계산해 고정하고 QUOTED + 유효기한 30일로 시작한다")
        void quoteComputesAndFreezesSnapshot() {
            ProposalQuote p = fcQuote();

            assertThat(p.getProposalId()).isNotBlank();
            assertThat(p.getStatus()).isEqualTo(ProposalStatus.QUOTED);
            // 1억 × 2.5 / 1000 = 250,000원
            assertThat(p.getAnnualPremium()).isEqualByComparingTo("250000");
            assertThat(p.getRateTableId()).isEqualTo(11L);
            assertThat(p.getAppliedRatePerMille()).isEqualByComparingTo("2.5");
            assertThat(p.getQuotedOn()).isEqualTo(QUOTED_ON);
            assertThat(p.getValidUntil()).isEqualTo(QUOTED_ON.plusDays(ProposalQuote.VALIDITY_DAYS));
            assertThat(p.getConvertedApplicationId()).isNull();
        }

        @Test
        @DisplayName("BANCA 설계는 판매 은행이 필수다 (채널 불변식 — Policy/청약과 동형)")
        void bancaRequiresPartnerBank() {
            assertThatThrownBy(() -> ProposalQuote.quote(
                    null, "PROD-LIFE-01", "bank-teller-1",
                    "홍길동", Gender.F, 40,
                    new BigDecimal("50000000"), 10,
                    new AppliedRate(11L, new BigDecimal("3.1")),
                    SalesChannel.BANCA, null, QUOTED_ON))
                    .isInstanceOf(InvalidSalesChannelException.class);
        }

        @Test
        @DisplayName("FC 설계가 판매 은행을 가지면 거부한다")
        void fcMustNotHavePartnerBank() {
            assertThatThrownBy(() -> ProposalQuote.quote(
                    null, "PROD-LIFE-01", "fc-100",
                    "홍길동", Gender.M, 37,
                    new BigDecimal("100000000"), 20,
                    new AppliedRate(11L, new BigDecimal("2.5")),
                    SalesChannel.FC, "BANK-001", QUOTED_ON))
                    .isInstanceOf(InvalidSalesChannelException.class);
        }

        @Test
        @DisplayName("재구성 시 CONVERTED 상태와 전환 청약 식별자는 함께여야 한다")
        void convertedRequiresApplicationId() {
            assertThatThrownBy(() -> ProposalQuote.builder()
                    .proposalId("p-1").productCode("PROD-LIFE-01").fcId("fc-100")
                    .insuredName("홍길동").insuredGender(Gender.M).insuranceAge(37)
                    .coverageAmount(new BigDecimal("100000000")).paymentTermYears(20)
                    .rateTableId(11L).appliedRatePerMille(new BigDecimal("2.5"))
                    .annualPremium(new BigDecimal("250000"))
                    .quotedOn(QUOTED_ON).validUntil(QUOTED_ON.plusDays(30))
                    .status(ProposalStatus.CONVERTED)  // convertedApplicationId 없음
                    .build())
                    .isInstanceOf(InvalidProposalException.class);
        }
    }

    @Nested
    @DisplayName("청약 전환(convert) — 유효기간 게이트 + 1설계 1청약")
    class Convert {

        @Test
        @DisplayName("유효기간 내 전환은 CONVERTED + 청약 식별자를 기록한다")
        void convertsWithinValidity() {
            ProposalQuote p = fcQuote();

            p.convert(APPLICATION_ID, p.getValidUntil());  // 유효기한 당일까지 허용

            assertThat(p.getStatus()).isEqualTo(ProposalStatus.CONVERTED);
            assertThat(p.getConvertedApplicationId()).isEqualTo(APPLICATION_ID);
        }

        @Test
        @DisplayName("유효기한 다음 날부터는 전환을 거부한다 — 상태는 그대로다")
        void rejectsAfterValidity() {
            ProposalQuote p = fcQuote();

            assertThatThrownBy(() -> p.convert(APPLICATION_ID, p.getValidUntil().plusDays(1)))
                    .isInstanceOf(ProposalExpiredException.class);
            assertThat(p.getStatus()).isEqualTo(ProposalStatus.QUOTED);
            assertThat(p.getConvertedApplicationId()).isNull();
        }

        @Test
        @DisplayName("이미 전환된 설계의 재전환은 차단된다")
        void rejectsDoubleConversion() {
            ProposalQuote p = fcQuote();
            p.convert(APPLICATION_ID, QUOTED_ON);

            assertThatThrownBy(() -> p.convert("other-app-id", QUOTED_ON))
                    .isInstanceOf(InvalidProposalTransitionException.class);
            // 원래 청약 식별자가 보존된다
            assertThat(p.getConvertedApplicationId()).isEqualTo(APPLICATION_ID);
        }

        @Test
        @DisplayName("EXPIRED 설계의 전환은 차단된다")
        void rejectsConversionFromExpired() {
            ProposalQuote p = fcQuote();
            p.expire(p.getValidUntil().plusDays(1));

            assertThatThrownBy(() -> p.convert(APPLICATION_ID, QUOTED_ON))
                    .isInstanceOf(ProposalExpiredException.class);
        }
    }

    @Nested
    @DisplayName("만기 처리(expire) — 스캔 술어·도메인 가드 동형")
    class Expire {

        @Test
        @DisplayName("유효기한 다음 날부터 EXPIRED 로 전이한다")
        void expiresAfterValidity() {
            ProposalQuote p = fcQuote();

            p.expire(p.getValidUntil().plusDays(1));

            assertThat(p.getStatus()).isEqualTo(ProposalStatus.EXPIRED);
        }

        @Test
        @DisplayName("유효기간이 지나지 않은 설계의 만기 처리는 거부한다")
        void rejectsPrematureExpiry() {
            ProposalQuote p = fcQuote();

            assertThatThrownBy(() -> p.expire(p.getValidUntil()))
                    .isInstanceOf(InvalidProposalException.class);
            assertThat(p.getStatus()).isEqualTo(ProposalStatus.QUOTED);
        }

        @Test
        @DisplayName("CONVERTED 설계의 만기 처리는 차단된다")
        void rejectsExpiryOfConverted() {
            ProposalQuote p = fcQuote();
            p.convert(APPLICATION_ID, QUOTED_ON);

            assertThatThrownBy(() -> p.expire(p.getValidUntil().plusDays(1)))
                    .isInstanceOf(InvalidProposalTransitionException.class);
        }
    }
}
