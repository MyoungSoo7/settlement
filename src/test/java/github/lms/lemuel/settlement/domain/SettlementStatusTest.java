package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SettlementStatus Enum")
class SettlementStatusTest {

    @Nested
    @DisplayName("fromString")
    class FromString {

        @Test
        @DisplayName("유효한 대문자 문자열을 파싱한다")
        void fromString_uppercase_succeeds() {
            assertThat(SettlementStatus.fromString("DONE")).isEqualTo(SettlementStatus.DONE);
            assertThat(SettlementStatus.fromString("REQUESTED")).isEqualTo(SettlementStatus.REQUESTED);
            assertThat(SettlementStatus.fromString("CONFIRMED")).isEqualTo(SettlementStatus.CONFIRMED);
        }

        @Test
        @DisplayName("소문자 문자열도 파싱한다")
        void fromString_lowercase_succeeds() {
            assertThat(SettlementStatus.fromString("done")).isEqualTo(SettlementStatus.DONE);
            assertThat(SettlementStatus.fromString("processing")).isEqualTo(SettlementStatus.PROCESSING);
        }

        @Test
        @DisplayName("존재하지 않는 값이면 기본값 REQUESTED를 반환한다")
        void fromString_invalid_returnsRequested() {
            assertThat(SettlementStatus.fromString("UNKNOWN")).isEqualTo(SettlementStatus.REQUESTED);
            assertThat(SettlementStatus.fromString("")).isEqualTo(SettlementStatus.REQUESTED);
        }
    }

    @Nested
    @DisplayName("canTransitionTo — 허용된 전이")
    class AllowedTransitions {

        @ParameterizedTest(name = "{0} → {1} 허용됨")
        @CsvSource({
            "REQUESTED, PROCESSING",
            "REQUESTED, CANCELED",
            "PROCESSING, DONE",
            "PROCESSING, FAILED",
            "FAILED, REQUESTED",
        })
        void allowed(SettlementStatus from, SettlementStatus to) {
            assertThat(from.canTransitionTo(to)).isTrue();
        }
    }

    @Nested
    @DisplayName("canTransitionTo — 금지된 전이")
    class ForbiddenTransitions {

        @ParameterizedTest(name = "{0} → {1} 불가")
        @CsvSource({
            "DONE, REQUESTED",
            "DONE, PROCESSING",
            "CANCELED, REQUESTED",
            "CANCELED, DONE",
            "REQUESTED, DONE",
            "PROCESSING, REQUESTED",
        })
        void forbidden(SettlementStatus from, SettlementStatus to) {
            assertThat(from.canTransitionTo(to)).isFalse();
        }
    }
}