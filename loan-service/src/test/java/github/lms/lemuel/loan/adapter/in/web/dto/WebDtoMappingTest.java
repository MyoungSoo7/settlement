package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase.CorporateCreditView;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * web 응답 DTO 의 from() 매핑 단위 테스트.
 */
class WebDtoMappingTest {

    @Test
    @DisplayName("CorporateCreditResponse.from 은 뷰 필드를 그대로 옮긴다")
    void corporateCreditResponse() {
        CorporateCreditView v = new CorporateCreditView("005930", "삼성전자", "KOSPI", 2025,
                82, "A", new BigDecimal("5000000"), new BigDecimal("40.2"),
                new BigDecimal("15.5"), new BigDecimal("8.1"), "B");

        CorporateCreditResponse r = CorporateCreditResponse.from(v);

        assertThat(r.stockCode()).isEqualTo("005930");
        assertThat(r.corpName()).isEqualTo("삼성전자");
        assertThat(r.market()).isEqualTo("KOSPI");
        assertThat(r.fiscalYear()).isEqualTo(2025);
        assertThat(r.creditScore()).isEqualTo(82);
        assertThat(r.creditGrade()).isEqualTo("A");
        assertThat(r.limit()).isEqualByComparingTo("5000000");
        assertThat(r.debtRatio()).isEqualByComparingTo("40.2");
        assertThat(r.operatingMargin()).isEqualByComparingTo("15.5");
        assertThat(r.roa()).isEqualByComparingTo("8.1");
        assertThat(r.reputationGrade()).isEqualTo("B");
    }

    @Test
    @DisplayName("CorporateLoanResponse.from 은 애그리거트 필드를 옮긴다")
    void corporateLoanResponse() {
        CorporateLoan loan = CorporateLoan.reconstitute(7L, "005930", "삼성전자",
                new BigDecimal("1000000"), new BigDecimal("6600"), new BigDecimal("1006600"),
                30, 82, "A", CorporateLoanStatus.DISBURSED, LocalDateTime.now());

        CorporateLoanResponse r = CorporateLoanResponse.from(loan);

        assertThat(r.id()).isEqualTo(7L);
        assertThat(r.stockCode()).isEqualTo("005930");
        assertThat(r.corpName()).isEqualTo("삼성전자");
        assertThat(r.principal()).isEqualByComparingTo("1000000");
        assertThat(r.fee()).isEqualByComparingTo("6600");
        assertThat(r.outstanding()).isEqualByComparingTo("1006600");
        assertThat(r.termDays()).isEqualTo(30);
        assertThat(r.creditScore()).isEqualTo(82);
        assertThat(r.creditGrade()).isEqualTo("A");
        assertThat(r.status()).isEqualTo(CorporateLoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("LoanResponse.from 은 선정산 대출 필드를 옮긴다")
    void loanResponse() {
        LoanAdvance loan = LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"),
                new BigDecimal("800"), new BigDecimal("800800"), LoanStatus.DISBURSED);

        LoanResponse r = LoanResponse.from(loan);

        assertThat(r.id()).isEqualTo(1L);
        assertThat(r.sellerId()).isEqualTo(7L);
        assertThat(r.principal()).isEqualByComparingTo("800000");
        assertThat(r.fee()).isEqualByComparingTo("800");
        assertThat(r.outstanding()).isEqualByComparingTo("800800");
        assertThat(r.status()).isEqualTo(LoanStatus.DISBURSED);
    }
}
