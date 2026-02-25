package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * OrderStatus Enum TDD Test
 *
 * 테스트 범위:
 * 1. Enum 값 검증
 * 2. fromString() 메서드
 * 3. 대소문자 처리
 */
@DisplayName("OrderStatus Enum")
class OrderStatusTest {

    @Test
    @DisplayName("모든 OrderStatus 값을 확인한다")
    void verifyAllOrderStatusValues() {
        // given & when
        OrderStatus[] statuses = OrderStatus.values();

        // then
        assertThat(statuses).hasSize(4);
        assertThat(statuses).containsExactlyInAnyOrder(
                OrderStatus.CREATED,
                OrderStatus.PAID,
                OrderStatus.CANCELED,
                OrderStatus.REFUNDED
        );
    }

    @Test
    @DisplayName("valueOf()로 정확한 이름의 OrderStatus를 가져온다")
    void valueOf_WithValidName_ReturnsStatus() {
        // when & then
        assertThat(OrderStatus.valueOf("CREATED")).isEqualTo(OrderStatus.CREATED);
        assertThat(OrderStatus.valueOf("PAID")).isEqualTo(OrderStatus.PAID);
        assertThat(OrderStatus.valueOf("CANCELED")).isEqualTo(OrderStatus.CANCELED);
        assertThat(OrderStatus.valueOf("REFUNDED")).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("valueOf()로 잘못된 이름 입력 시 예외가 발생한다")
    void valueOf_WithInvalidName_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> OrderStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, CREATED",
            "PAID, PAID",
            "CANCELED, CANCELED",
            "REFUNDED, REFUNDED"
    })
    @DisplayName("fromString()은 대문자 문자열을 올바른 OrderStatus로 변환한다")
    void fromString_WithUpperCase_ReturnsCorrectStatus(String input, OrderStatus expected) {
        // when
        OrderStatus result = OrderStatus.fromString(input);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "created, CREATED",
            "paid, PAID",
            "canceled, CANCELED",
            "refunded, REFUNDED"
    })
    @DisplayName("fromString()은 소문자 문자열을 올바른 OrderStatus로 변환한다")
    void fromString_WithLowerCase_ReturnsCorrectStatus(String input, OrderStatus expected) {
        // when
        OrderStatus result = OrderStatus.fromString(input);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "Created, CREATED",
            "PaId, PAID",
            "CaNcElEd, CANCELED",
            "rEfUnDeD, REFUNDED"
    })
    @DisplayName("fromString()은 대소문자 혼합 문자열을 올바른 OrderStatus로 변환한다")
    void fromString_WithMixedCase_ReturnsCorrectStatus(String input, OrderStatus expected) {
        // when
        OrderStatus result = OrderStatus.fromString(input);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID", "UNKNOWN", "PENDING", "", "   "})
    @DisplayName("fromString()은 잘못된 문자열 입력 시 CREATED를 기본값으로 반환한다")
    void fromString_WithInvalidInput_ReturnsDefaultStatus(String input) {
        // when
        OrderStatus result = OrderStatus.fromString(input);

        // then
        assertThat(result).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("fromString()은 null 입력 시 CREATED를 기본값으로 반환한다")
    void fromString_WithNull_ReturnsDefaultStatus() {
        // when
        OrderStatus result = OrderStatus.fromString(null);

        // then
        assertThat(result).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("OrderStatus는 name()으로 문자열 이름을 반환한다")
    void name_ReturnsStringName() {
        // when & then
        assertThat(OrderStatus.CREATED.name()).isEqualTo("CREATED");
        assertThat(OrderStatus.PAID.name()).isEqualTo("PAID");
        assertThat(OrderStatus.CANCELED.name()).isEqualTo("CANCELED");
        assertThat(OrderStatus.REFUNDED.name()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("OrderStatus는 ordinal()로 순서 인덱스를 반환한다")
    void ordinal_ReturnsOrderIndex() {
        // when & then
        assertThat(OrderStatus.CREATED.ordinal()).isEqualTo(0);
        assertThat(OrderStatus.PAID.ordinal()).isEqualTo(1);
        assertThat(OrderStatus.CANCELED.ordinal()).isEqualTo(2);
        assertThat(OrderStatus.REFUNDED.ordinal()).isEqualTo(3);
    }

    @Test
    @DisplayName("동일한 OrderStatus는 같은 객체 참조를 가진다")
    void sameStatusHasSameReference() {
        // when
        OrderStatus status1 = OrderStatus.CREATED;
        OrderStatus status2 = OrderStatus.valueOf("CREATED");
        OrderStatus status3 = OrderStatus.fromString("created");

        // then
        assertThat(status1).isSameAs(status2);
        assertThat(status1).isSameAs(status3);
    }
}
