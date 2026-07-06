package github.lms.lemuel.financial.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FinancialStatementTest {

    private static FinancialStatement statement(BigDecimal revenue, BigDecimal operatingProfit,
                                                BigDecimal netIncome, BigDecimal assets,
                                                BigDecimal liabilities, BigDecimal equity) {
        return new FinancialStatement(null, "005930", 2024, FsDivision.CFS, "KRW",
                revenue, operatingProfit, netIncome, assets, liabilities, equity,
                StatementSource.SEED, null);
    }

    private static BigDecimal won(long billions) {
        return BigDecimal.valueOf(billions).multiply(BigDecimal.valueOf(1_000_000_000L));
    }

    @Test
    @DisplayName("영업이익률/순이익률 — 매출 대비 % 를 소수 2자리로 계산한다")
    void margins() {
        FinancialStatement s = statement(won(1000), won(150), won(100), won(2000), won(800), won(1200));

        assertThat(s.operatingMargin()).isEqualByComparingTo("15.00");
        assertThat(s.netMargin()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("매출이 null(금융지주 등)이면 이익률은 null — N/A 처리")
    void marginsWithoutRevenue() {
        FinancialStatement s = statement(null, won(150), won(100), won(2000), won(800), won(1200));

        assertThat(s.operatingMargin()).isNull();
        assertThat(s.netMargin()).isNull();
    }

    @Test
    @DisplayName("부채비율 = 부채/자본 ×100, 자기자본비율 = 자본/자산 ×100, ROA = 순이익/자산 ×100")
    void balanceSheetRatios() {
        FinancialStatement s = statement(won(1000), won(150), won(100), won(2000), won(800), won(1200));

        assertThat(s.debtRatio()).isEqualByComparingTo("66.67");
        assertThat(s.equityRatio()).isEqualByComparingTo("60.00");
        assertThat(s.roa()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("자본잠식(자본총계 ≤ 0)이면 부채비율은 null")
    void debtRatioOnImpairedEquity() {
        FinancialStatement s = statement(won(1000), won(150), won(100), won(2000), won(2100), won(-100));

        assertThat(s.debtRatio()).isNull();
    }

    @Test
    @DisplayName("분모가 0 이면 지표는 null (0 나눗셈 방어)")
    void zeroDenominator() {
        FinancialStatement s = statement(BigDecimal.ZERO, won(150), won(100), BigDecimal.ZERO, won(800), won(1200));

        assertThat(s.operatingMargin()).isNull();
        assertThat(s.equityRatio()).isNull();
        assertThat(s.roa()).isNull();
    }

    @Test
    @DisplayName("재무상태표 항등식 — 자산 = 부채 + 자본이면 허용오차 내 balanced")
    void balanced() {
        FinancialStatement exact = statement(won(1000), won(150), won(100), won(2000), won(800), won(1200));
        FinancialStatement off = statement(won(1000), won(150), won(100), won(2000), won(800), won(1100));

        assertThat(exact.isBalanced(new BigDecimal("0.5"))).isTrue();
        assertThat(off.isBalanced(new BigDecimal("0.5"))).isFalse();   // 5% 차이 > 0.5% 허용오차
    }

    @Test
    @DisplayName("자산/부채/자본 중 하나라도 없으면 항등식 검증 불가 → false")
    void balancedWithMissingAccounts() {
        FinancialStatement s = statement(won(1000), won(150), won(100), null, won(800), won(1200));

        assertThat(s.isBalanced(new BigDecimal("0.5"))).isFalse();
    }

    @Test
    @DisplayName("음수 실적(적자)도 그대로 지표에 반영된다")
    void negativeProfits() {
        FinancialStatement s = statement(won(1000), won(-77), won(-91), won(2000), won(800), won(1200));

        assertThat(s.operatingMargin()).isEqualByComparingTo("-7.70");
        assertThat(s.netMargin()).isEqualByComparingTo("-9.10");
    }

    @Test
    @DisplayName("검증 — 종목코드 6자리, 사업연도 범위, 구분/출처 필수")
    void validation() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new FinancialStatement(null, "12345", 2024, FsDivision.CFS, "KRW",
                        null, null, null, null, null, null, StatementSource.SEED, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new FinancialStatement(null, "005930", 1901, FsDivision.CFS, "KRW",
                        null, null, null, null, null, null, StatementSource.SEED, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new FinancialStatement(null, "005930", 2024, null, "KRW",
                        null, null, null, null, null, null, StatementSource.SEED, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new FinancialStatement(null, "005930", 2024, FsDivision.CFS, "KRW",
                        null, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("통화 미지정 시 KRW 기본값")
    void defaultCurrency() {
        FinancialStatement s = new FinancialStatement(null, "005930", 2024, FsDivision.CFS, null,
                null, null, null, null, null, null, StatementSource.DART, null);

        assertThat(s.currency()).isEqualTo("KRW");
    }
}
