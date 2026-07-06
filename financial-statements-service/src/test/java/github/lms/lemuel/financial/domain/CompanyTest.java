package github.lms.lemuel.financial.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CompanyTest {

    @Test
    @DisplayName("시드 기업은 corp_code 없이 생성 가능, market 미지정 시 KOSPI")
    void seedCompany() {
        Company company = new Company("005930", null, "삼성전자", null);

        assertThat(company.hasCorpCode()).isFalse();
        assertThat(company.market()).isEqualTo("KOSPI");
    }

    @Test
    @DisplayName("mergedWith — DART 동기화가 corp_code 와 최신 기업명을 채운다")
    void mergedWith() {
        Company seed = new Company("005930", null, "삼성전자", "KOSPI");

        Company merged = seed.mergedWith("00126380", "삼성전자");

        assertThat(merged.corpCode()).isEqualTo("00126380");
        assertThat(merged.stockCode()).isEqualTo("005930");
        assertThat(merged.hasCorpCode()).isTrue();
    }

    @Test
    @DisplayName("mergedWith — 새 이름이 비어 있으면 기존 이름 유지")
    void mergedWithBlankName() {
        Company seed = new Company("005930", null, "삼성전자", "KOSPI");

        assertThat(seed.mergedWith("00126380", " ").name()).isEqualTo("삼성전자");
    }

    @Test
    @DisplayName("검증 — 종목코드 6자리, corp_code 8자리, 기업명 필수")
    void validation() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Company("59", null, "삼성전자", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Company("005930", "126380", "삼성전자", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Company("005930", null, " ", null));
    }
}
