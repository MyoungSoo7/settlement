package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyWorkforceTest {

    private CompanyWorkforce valid(int headcount, String monthlyBilledAmount) {
        return new CompanyWorkforce("주식회사에고이즘", "866759", "525101", "전자상거래 소매업",
                "서울특별시 성동구 연무장19길", YearMonth.of(2026, 6), headcount, new BigDecimal(monthlyBilledAmount));
    }

    private CompanyWorkforce with(String industryCode, String address) {
        return new CompanyWorkforce("주식회사에고이즘", "866759", industryCode, "전자상거래 소매업",
                address, YearMonth.of(2026, 6), 50, new BigDecimal("16406250"));
    }

    @Test
    @DisplayName("사업장명 누락은 거부한다")
    void rejectsBlankWorkplaceName() {
        assertThrows(IllegalArgumentException.class, () ->
                new CompanyWorkforce(" ", "866759", "525101", "전자상거래 소매업", "주소",
                        YearMonth.of(2026, 6), 50, new BigDecimal("1000000")));
    }

    @Test
    @DisplayName("기준월 누락은 생성 시점에 거부한다 — 나중에 NPE 로 터지게 두지 않는다")
    void rejectsNullSnapshotMonth() {
        // 기준월은 보험료율·상한 조회(NpsContributionRate/NpsIncomeCap)와 응답 직렬화가 모두 의존하는 필수값이다.
        // null 을 허용하면 생성은 통과하고 한참 뒤 추정연봉 계산이나 목록 응답에서 NPE 로 터진다.
        assertThrows(IllegalArgumentException.class, () ->
                new CompanyWorkforce("주식회사에고이즘", "866759", "525101", "전자상거래 소매업", "주소",
                        null, 50, new BigDecimal("1000000")));
    }

    @Test
    @DisplayName("가입자수는 음수를 거부한다")
    void rejectsNegativeHeadcount() {
        assertThrows(IllegalArgumentException.class, () -> valid(-1, "1000000"));
    }

    @Test
    @DisplayName("당월고지금액은 음수를 거부한다")
    void rejectsNegativeMonthlyBilledAmount() {
        assertThrows(IllegalArgumentException.class, () -> valid(4, "-1"));
    }

    @Test
    @DisplayName("추정연봉 = (당월고지금액×12) / (가입자수×기준월 보험료율) — 국민연금 대납 보험료 역산")
    void estimatesAnnualSalaryFromPensionContribution() {
        CompanyWorkforce workforce = valid(4, "943140");
        Optional<BigDecimal> estimated = workforce.estimatedAnnualSalary();
        assertTrue(estimated.isPresent());
        assertEquals(0, new BigDecimal("29783368").compareTo(estimated.get()));
    }

    @Test
    @DisplayName("2026년 6월은 인상된 국민연금 보험료율로 추정연봉을 계산한다")
    void estimatesAnnualSalaryUsing2026NpsContributionRate() {
        CompanyWorkforce workforce = valid(10, "950000");

        assertEquals(Optional.of(new BigDecimal("12000000")), workforce.estimatedAnnualSalary());
    }

    @Test
    @DisplayName("가입자수 0이면 추정연봉을 계산하지 않는다")
    void noEstimateWhenHeadcountZero() {
        CompanyWorkforce workforce = valid(0, "0");
        assertEquals(Optional.empty(), workforce.estimatedAnnualSalary());
    }

    @Test
    @DisplayName("보험료율 지원 범위 밖 기준월이면 추정연봉을 계산하지 않는다")
    void noEstimateWhenSnapshotMonthHasNoSupportedRate() {
        CompanyWorkforce workforce = new CompanyWorkforce("미래회사", "111111", "525101", "업종", "서울특별시 성동구",
                YearMonth.of(2027, 1), 1, new BigDecimal("950000"));

        assertEquals(Optional.empty(), workforce.estimatedAnnualSalary());
    }

    @Test
    @DisplayName("업종 집단 키는 6자리 코드, 상위 집단 키는 앞 3자리다")
    void industryGroupKeys() {
        CompanyWorkforce workforce = with("525101", "서울특별시 성동구 연무장19길");

        assertEquals(Optional.of("525101"), workforce.industryGroupKey());
        assertEquals(Optional.of("525"), workforce.industryRollupKey());
    }

    @Test
    @DisplayName("업종코드 공란 행은 업종 집단 키가 없다 — 원본에 미신고 공란이 실제로 존재")
    void missingIndustryCode() {
        for (String blank : new String[]{null, "", "   "}) {
            CompanyWorkforce workforce = with(blank, "서울특별시 성동구 연무장19길");

            assertEquals(Optional.empty(), workforce.industryGroupKey());
            assertEquals(Optional.empty(), workforce.industryRollupKey());
        }
    }

    @Test
    @DisplayName("업종코드가 3자리 이하면 상위 집단 키는 코드 자체다")
    void shortIndustryCodeRollsUpToItself() {
        assertEquals(Optional.of("52"), with("52", "서울특별시 성동구").industryRollupKey());
    }

    @Test
    @DisplayName("지역은 주소에서 파생한다")
    void regionDerivedFromAddress() {
        WorkplaceRegion region = with("525101", "서울특별시 성동구 연무장19길").region();

        assertEquals("서울특별시", region.sido());
        assertEquals("성동구", region.sigungu());
    }

    @Test
    @DisplayName("추정연봉이 기준소득월액 상한액×12 에 도달하면 상한 도달로 표시한다")
    void salaryCapReachedAtExactlyTwelveTimesCap() {
        // 2026-06 상한액 6,370,000(2025-07~ 구간) → 연 76,440,000.
        // 가입자 1명 · 고지 605,150 이면 추정연봉이 정확히 상한 = 경계값.
        CompanyWorkforce atCap = valid(1, "605150");

        assertEquals(0, new BigDecimal("76440000").compareTo(atCap.estimatedAnnualSalary().orElseThrow()));
        assertTrue(atCap.salaryCapReached());
        assertEquals(Optional.of(new BigDecimal("6370000")), atCap.salaryCapMonthlyAmount());
    }

    @Test
    @DisplayName("상한 미달이면 false — 1원 차이도 경계 밖")
    void salaryCapNotReached() {
        assertFalse(valid(1, "605149").salaryCapReached());
        assertFalse(valid(4, "943140").salaryCapReached());
    }

    @Test
    @DisplayName("고시표 범위 밖 기준월은 상한액을 제공하지 않고 도달 판정도 하지 않는다")
    void salaryCapUnknownOutsideNoticeTable() {
        CompanyWorkforce old = new CompanyWorkforce("옛회사", "111111", "525101", "업종", "서울특별시 성동구",
                YearMonth.of(2021, 1), 1, new BigDecimal("99999999"));

        assertEquals(Optional.empty(), old.salaryCapMonthlyAmount());
        assertFalse(old.salaryCapReached());
    }

    @Test
    @DisplayName("지표별 대상 값은 도메인이 답한다 — 추정연봉은 산출 불가일 수 있다")
    void answersMetricValue() {
        CompanyWorkforce workforce = valid(4, "943140");

        assertEquals(Optional.of(new BigDecimal("4")), workforce.valueOf(WorkforceMetric.HEADCOUNT));
        assertEquals(0, new BigDecimal("29783368")
                .compareTo(workforce.valueOf(WorkforceMetric.ESTIMATED_ANNUAL_SALARY).orElseThrow()));
        assertEquals(Optional.empty(), valid(0, "0").valueOf(WorkforceMetric.ESTIMATED_ANNUAL_SALARY));
    }

    @Test
    @DisplayName("가입자수 0 또는 고지금액 0 이면 비교 모집단에서 제외한다 — 추정연봉을 산출할 수 없다")
    void eligibilityRequiresPositiveHeadcountAndAmount() {
        assertTrue(valid(4, "943140").eligibleForComparison());
        assertFalse(valid(0, "943140").eligibleForComparison());
        assertFalse(valid(4, "0").eligibleForComparison());
    }
}
