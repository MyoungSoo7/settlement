package github.lms.lemuel.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * UserRole Enum TDD Test
 */
@DisplayName("UserRole 도메인 Enum")
class UserRoleTest {

    @Test
    @DisplayName("모든 역할이 정의되어 있다")
    void allRolesDefined() {
        // when
        UserRole[] roles = UserRole.values();

        // then
        assertThat(roles).hasSize(3);
        assertThat(roles).contains(UserRole.USER, UserRole.ADMIN, UserRole.MANAGER);
    }

    @ParameterizedTest
    @CsvSource({
            "USER, USER",
            "ADMIN, ADMIN",
            "MANAGER, MANAGER"
    })
    @DisplayName("fromString()은 대문자 문자열을 올바른 Role로 변환한다")
    void fromString_WithUpperCase_ReturnsCorrectRole(String input, UserRole expected) {
        // when
        UserRole result = UserRole.fromString(input);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "user, USER",
            "admin, ADMIN",
            "manager, MANAGER"
    })
    @DisplayName("fromString()은 소문자 문자열을 올바른 Role로 변환한다")
    void fromString_WithLowerCase_ReturnsCorrectRole(String input, UserRole expected) {
        // when
        UserRole result = UserRole.fromString(input);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "User, USER",
            "Admin, ADMIN",
            "MaNaGeR, MANAGER"
    })
    @DisplayName("fromString()은 대소문자 혼합 문자열을 올바른 Role로 변환한다")
    void fromString_WithMixedCase_ReturnsCorrectRole(String input, UserRole expected) {
        // when
        UserRole result = UserRole.fromString(input);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID", "SUPER_ADMIN", "GUEST", "", "   "})
    @DisplayName("fromString()은 잘못된 문자열에 대해 USER를 기본값으로 반환한다")
    void fromString_WithInvalidString_ReturnsDefaultUser(String input) {
        // when
        UserRole result = UserRole.fromString(input);

        // then
        assertThat(result).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("fromString()은 null에 대해 USER를 기본값으로 반환한다")
    void fromString_WithNull_ReturnsDefaultUser() {
        // when
        UserRole result = UserRole.fromString(null);

        // then
        assertThat(result).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("valueOf()는 정확한 Enum 상수를 반환한다")
    void valueOf_ReturnsExactEnum() {
        // when
        UserRole user = UserRole.valueOf("USER");
        UserRole admin = UserRole.valueOf("ADMIN");
        UserRole manager = UserRole.valueOf("MANAGER");

        // then
        assertThat(user).isEqualTo(UserRole.USER);
        assertThat(admin).isEqualTo(UserRole.ADMIN);
        assertThat(manager).isEqualTo(UserRole.MANAGER);
    }

    @Test
    @DisplayName("name()은 Enum 이름을 문자열로 반환한다")
    void name_ReturnsEnumName() {
        // when & then
        assertThat(UserRole.USER.name()).isEqualTo("USER");
        assertThat(UserRole.ADMIN.name()).isEqualTo("ADMIN");
        assertThat(UserRole.MANAGER.name()).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("Enum 비교는 == 연산자로 수행할 수 있다")
    void enumComparison_WorksWithEqualityOperator() {
        // given
        UserRole role1 = UserRole.ADMIN;
        UserRole role2 = UserRole.ADMIN;
        UserRole role3 = UserRole.USER;

        // when & then
        assertThat(role1 == role2).isTrue();
        assertThat(role1 == role3).isFalse();
    }
}
