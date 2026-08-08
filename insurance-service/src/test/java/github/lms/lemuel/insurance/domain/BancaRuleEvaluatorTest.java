package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BancaRuleViolation;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BankInsurerPremium;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static github.lms.lemuel.insurance.domain.InsurerSector.LIFE;
import static github.lms.lemuel.insurance.domain.InsurerSector.NON_LIFE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방카 25%룰 판정 도메인 테스트 — 경계값 3종이 핵심:
 * 정확히 25%(허용) · 정확히 자산 2조(적용 대상) · 부문 분리 분모.
 */
@DisplayName("BancaRuleEvaluator — 방카 25%룰")
class BancaRuleEvaluatorTest {

    /** 자산 미등록 상태 — 모든 은행이 적용 대상(fail-closed)이 된다. */
    private static final Map<String, BigDecimal> NO_ASSETS = Map.of();

    private static BankInsurerPremium premium(
            String bank, InsurerSector sector, String insurer, String amount) {
        return new BankInsurerPremium(bank, sector, insurer, new BigDecimal(amount));
    }

    @Test
    @DisplayName("특정 원수사 비중이 25% 를 초과하면 위반이다")
    void detectsShareAboveLimit() {
        // BANK-KB 생보 총 100만: INS-A 40만(40% → 위반) / INS-B·C·D 각 20만(20% → 정상)
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-KB", LIFE, "INS-A", "400000.00"),
                premium("BANK-KB", LIFE, "INS-B", "200000.00"),
                premium("BANK-KB", LIFE, "INS-C", "200000.00"),
                premium("BANK-KB", LIFE, "INS-D", "200000.00")), NO_ASSETS);

        assertThat(violations).hasSize(1);
        BancaRuleViolation v = violations.get(0);
        assertThat(v.bankCode()).isEqualTo("BANK-KB");
        assertThat(v.sector()).isEqualTo(LIFE);
        assertThat(v.insurerCode()).isEqualTo("INS-A");
        assertThat(v.share()).isEqualByComparingTo(new BigDecimal("0.4000"));
        assertThat(v.sectorPremiumTotal()).isEqualByComparingTo(new BigDecimal("1000000.00"));
    }

    @Test
    @DisplayName("정확히 25% 는 허용이다 — 상한은 초과 기준")
    void allowsExactlyTwentyFivePercent() {
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-KB", LIFE, "INS-A", "250000.00"),
                premium("BANK-KB", LIFE, "INS-B", "250000.00"),
                premium("BANK-KB", LIFE, "INS-C", "250000.00"),
                premium("BANK-KB", LIFE, "INS-D", "250000.00")), NO_ASSETS);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("은행별로 독립 판정한다 — 한 은행의 위반이 다른 은행에 전이되지 않는다")
    void evaluatesPerBank() {
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-KB", LIFE, "INS-A", "900000.00"),   // KB: INS-A 90% → 위반
                premium("BANK-KB", LIFE, "INS-B", "100000.00"),
                premium("BANK-SH", LIFE, "INS-A", "200000.00"),   // SH: INS-A 20% → 정상
                premium("BANK-SH", LIFE, "INS-B", "800000.00")), NO_ASSETS);  // SH: INS-B 80% → 위반

        assertThat(violations).extracting(v -> v.bankCode() + "/" + v.insurerCode())
                .containsExactlyInAnyOrder("BANK-KB/INS-A", "BANK-SH/INS-B");
    }

    @Test
    @DisplayName("단일 원수사만 파는 은행은 100% 로 위반이다")
    void singleInsurerBankIsAlwaysViolation() {
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-MONO", LIFE, "INS-A", "10000.00")), NO_ASSETS);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).share()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("집계가 비면 위반도 없다")
    void emptyInputYieldsNoViolations() {
        assertThat(BancaRuleEvaluator.evaluate(List.of(), NO_ASSETS)).isEmpty();
    }

    @Test
    @DisplayName("비중은 소수 4자리 HALF_UP — 1/3 은 0.3333")
    void roundsShareToFourDecimals() {
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-KB", LIFE, "INS-A", "100.00"),
                premium("BANK-KB", LIFE, "INS-B", "100.00"),
                premium("BANK-KB", LIFE, "INS-C", "100.00")), NO_ASSETS);

        assertThat(violations).hasSize(3);
        assertThat(violations.get(0).share()).isEqualByComparingTo(new BigDecimal("0.3333"));
    }

    @Nested
    @DisplayName("생보/손보 부문 분리 계산 (V8)")
    class SectorSeparation {

        @Test
        @DisplayName("비중의 분모는 (은행, 부문) 총액이다 — 은행 전체 합산이 아니다")
        void separatesSectorPoolsWithinBank() {
            // BANK-KB 전체 50만이지만 부문별로 보면:
            //   생보 풀 10만: INS-A 10만 (100% → 위반. 은행 합산이면 20% 로 숨는다)
            //   손보 풀 40만: INS-B 30만 (75% → 위반) / INS-C 10만 (25% → 허용)
            List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                    premium("BANK-KB", LIFE,     "INS-A", "100000.00"),
                    premium("BANK-KB", NON_LIFE, "INS-B", "300000.00"),
                    premium("BANK-KB", NON_LIFE, "INS-C", "100000.00")), NO_ASSETS);

            assertThat(violations)
                    .extracting(v -> v.sector() + "/" + v.insurerCode())
                    .containsExactlyInAnyOrder("LIFE/INS-A", "NON_LIFE/INS-B");

            BancaRuleViolation life = violations.stream()
                    .filter(v -> v.sector() == LIFE).findFirst().orElseThrow();
            assertThat(life.share()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(life.sectorPremiumTotal()).isEqualByComparingTo(new BigDecimal("100000.00"));

            BancaRuleViolation nonLife = violations.stream()
                    .filter(v -> v.sector() == NON_LIFE).findFirst().orElseThrow();
            assertThat(nonLife.share()).isEqualByComparingTo(new BigDecimal("0.7500"));
            assertThat(nonLife.sectorPremiumTotal()).isEqualByComparingTo(new BigDecimal("400000.00"));
        }

        @Test
        @DisplayName("같은 원수사라도 부문이 다르면 각각 독립 판정한다")
        void sameInsurerJudgedPerSector() {
            // INS-A 가 생보 풀에선 80%(위반), 손보 풀에선 20%(정상)
            List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                    premium("BANK-KB", LIFE,     "INS-A", "800000.00"),
                    premium("BANK-KB", LIFE,     "INS-B", "200000.00"),
                    premium("BANK-KB", NON_LIFE, "INS-A", "200000.00"),
                    premium("BANK-KB", NON_LIFE, "INS-B", "800000.00")), NO_ASSETS);

            assertThat(violations)
                    .extracting(v -> v.sector() + "/" + v.insurerCode())
                    .containsExactlyInAnyOrder("LIFE/INS-A", "NON_LIFE/INS-B");
        }
    }

    @Nested
    @DisplayName("자산 2조 적용 요건 (V8)")
    class AssetApplicability {

        @Test
        @DisplayName("자산 2조 미만으로 등록된 은행은 면제다 — 100% 집중이어도 위반이 아니다")
        void exemptsBankUnderAssetThreshold() {
            List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(
                    List.of(premium("BANK-SMALL", LIFE, "INS-A", "500000.00")),
                    Map.of("BANK-SMALL", new BigDecimal("1999999999999.99")));

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("정확히 2조는 적용 대상이다 — '이상' 기준(경계 포함)")
        void exactlyTwoTrillionIsSubject() {
            List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(
                    List.of(premium("BANK-EDGE", LIFE, "INS-A", "500000.00")),
                    Map.of("BANK-EDGE", new BigDecimal("2000000000000")));

            assertThat(violations).hasSize(1);
        }

        @Test
        @DisplayName("자산 미등록 은행은 적용 대상으로 본다 — fail-closed 모니터링")
        void unknownAssetBankIsSubject() {
            List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(
                    List.of(premium("BANK-UNKNOWN", LIFE, "INS-A", "500000.00")),
                    Map.of("BANK-OTHER", new BigDecimal("1000000000000")));

            assertThat(violations).hasSize(1);
        }

        @Test
        @DisplayName("면제는 은행 단위다 — 같은 집계 안의 다른 은행 판정에 영향이 없다")
        void exemptionIsPerBank() {
            List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                    premium("BANK-SMALL", LIFE, "INS-A", "500000.00"),   // 면제
                    premium("BANK-BIG",   LIFE, "INS-A", "500000.00")),  // 적용 → 100% 위반
                    Map.of(
                            "BANK-SMALL", new BigDecimal("500000000000"),
                            "BANK-BIG",   new BigDecimal("3000000000000")));

            assertThat(violations).hasSize(1);
            assertThat(violations.get(0).bankCode()).isEqualTo("BANK-BIG");
        }

        @Test
        @DisplayName("isSubjectToRule — null(미등록)은 적용, 2조 이상 적용, 미만 면제")
        void isSubjectToRuleBoundaries() {
            assertThat(BancaRuleEvaluator.isSubjectToRule(null)).isTrue();
            assertThat(BancaRuleEvaluator.isSubjectToRule(new BigDecimal("2000000000000"))).isTrue();
            assertThat(BancaRuleEvaluator.isSubjectToRule(new BigDecimal("1999999999999.99"))).isFalse();
        }
    }
}
