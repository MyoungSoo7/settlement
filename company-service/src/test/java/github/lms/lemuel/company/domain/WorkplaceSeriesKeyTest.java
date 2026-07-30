package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkplaceSeriesKeyTest {

    @Test
    @DisplayName("정상 키 생성 — 사업장명 + 사업자번호 앞 6자리")
    void createsValidKey() {
        WorkplaceSeriesKey key = WorkplaceSeriesKey.of("주식회사에고이즘", "866759");

        assertThat(key.workplaceName()).isEqualTo("주식회사에고이즘");
        assertThat(key.bizRegNoPrefix()).isEqualTo("866759");
    }

    @Test
    @DisplayName("검증 순서는 name → bizRegNoPrefix — 둘 다 잘못돼도 name 오류가 먼저다")
    void validatesNameFirst() {
        assertThatThrownBy(() -> WorkplaceSeriesKey.of(null, "86675"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사업장명(name)은 필수입니다");
    }

    @Test
    @DisplayName("사업자번호 앞 6자리는 숫자 6자리 형식을 강제한다")
    void validatesPrefixFormat() {
        assertThatThrownBy(() -> WorkplaceSeriesKey.of("주식회사에고이즘", "86675"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("숫자 6자리");
        assertThatThrownBy(() -> WorkplaceSeriesKey.of("주식회사에고이즘", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수");
    }

    @Test
    @DisplayName("사업장명은 컬럼 폭 200자를 넘을 수 없다")
    void validatesNameLength() {
        assertThatThrownBy(() -> WorkplaceSeriesKey.of("가".repeat(201), "866759"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200자");
    }
}
