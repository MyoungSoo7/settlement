package github.lms.lemuel.report.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BucketGranularity — groupBy 파싱")
class BucketGranularityTest {

    @Test
    @DisplayName("null/blank 는 DAY 로 폴백")
    void from_nullOrBlank_defaultsToDay() {
        assertThat(BucketGranularity.from(null)).isEqualTo(BucketGranularity.DAY);
        assertThat(BucketGranularity.from("")).isEqualTo(BucketGranularity.DAY);
        assertThat(BucketGranularity.from("   ")).isEqualTo(BucketGranularity.DAY);
    }

    @Test
    @DisplayName("유효 값은 대소문자·공백 무관하게 파싱")
    void from_validValues() {
        assertThat(BucketGranularity.from("day")).isEqualTo(BucketGranularity.DAY);
        assertThat(BucketGranularity.from("WEEK")).isEqualTo(BucketGranularity.WEEK);
        assertThat(BucketGranularity.from("  month ")).isEqualTo(BucketGranularity.MONTH);
    }

    @Test
    @DisplayName("지원하지 않는 값은 예외")
    void from_unsupported_throws() {
        assertThatThrownBy(() -> BucketGranularity.from("year"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported groupBy");
    }
}
