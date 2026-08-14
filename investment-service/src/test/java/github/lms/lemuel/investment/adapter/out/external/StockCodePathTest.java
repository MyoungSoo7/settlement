package github.lms.lemuel.investment.adapter.out.external;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 종목코드 경로 관문 — 요청에서 온 값이 위성 서비스 URL 경로로 흘러가기 전에 막는다.
 *
 * <p>막지 않으면 {@code GET /api/investment/checks/{stockCode}} 의 경로 변수가 내부 서비스의 다른
 * 경로를 호출하는 통로가 된다(경로 조작).
 */
class StockCodePathTest {

    @Test
    @DisplayName("KRX 6자리 종목코드는 그대로 통과한다")
    void allowsPlainStockCode() {
        assertThat(StockCodePath.segment("005930")).isEqualTo("005930");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../admin/datasources",   // 경로 상위 이동
            "005930/../../admin",        // 정상 코드 뒤에 붙인 이동
            "..%2f..%2fadmin",           // 인코딩 우회
            "005930?size=99",            // 질의 문자열 주입
            "005930#frag",
            "005 930",                   // 공백
            "",                          // 빈 값
    })
    @DisplayName("경로 구분자·인코딩 우회·질의 문자가 섞이면 거부한다")
    void rejectsPathManipulation(String malicious) {
        assertThatThrownBy(() -> StockCodePath.segment(malicious))
                .isInstanceOf(InvestmentInvariantViolationException.class)
                .hasMessageContaining("종목코드");
    }

    @Test
    @DisplayName("null 도 거부한다")
    void rejectsNull() {
        assertThatThrownBy(() -> StockCodePath.segment(null))
                .isInstanceOf(InvestmentInvariantViolationException.class);
    }
}
