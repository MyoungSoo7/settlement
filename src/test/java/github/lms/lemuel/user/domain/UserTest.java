package github.lms.lemuel.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * User Domain Entity TDD Test
 *
 * 테스트 범위:
 * 1. 생성자 및 팩토리 메서드
 * 2. 이메일 검증
 * 3. 비밀번호 검증
 * 4. 비즈니스 메서드
 */
@DisplayName("User 도메인 엔티티")
class UserTest {

    @Nested
    @DisplayName("생성자 테스트")
    class ConstructorTest {

        @Test
        @DisplayName("기본 생성자로 User 생성 시 기본값이 설정된다")
        void defaultConstructor() {
            // when
            User user = new User();

            // then
            assertThat(user.getRole()).isEqualTo(UserRole.USER);
            assertThat(user.getCreatedAt()).isNotNull();
            assertThat(user.getUpdatedAt()).isNotNull();
            assertThat(user.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        }

        @Test
        @DisplayName("전체 생성자로 User 생성 시 모든 필드가 설정된다")
        void allArgsConstructor() {
            // given
            Long id = 1L;
            String email = "test@example.com";
            String passwordHash = "hashedPassword123";
            UserRole role = UserRole.ADMIN;
            LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
            LocalDateTime updatedAt = LocalDateTime.now();

            // when
            User user = new User(id, email, passwordHash, role, createdAt, updatedAt);

            // then
            assertThat(user.getId()).isEqualTo(id);
            assertThat(user.getEmail()).isEqualTo(email);
            assertThat(user.getPasswordHash()).isEqualTo(passwordHash);
            assertThat(user.getRole()).isEqualTo(role);
            assertThat(user.getCreatedAt()).isEqualTo(createdAt);
            assertThat(user.getUpdatedAt()).isEqualTo(updatedAt);
        }

        @Test
        @DisplayName("전체 생성자에서 role이 null이면 USER로 기본 설정된다")
        void allArgsConstructor_WithNullRole_SetsDefaultRole() {
            // when
            User user = new User(1L, "test@example.com", "hash", null, null, null);

            // then
            assertThat(user.getRole()).isEqualTo(UserRole.USER);
        }

        @Test
        @DisplayName("전체 생성자에서 timestamp가 null이면 현재 시간으로 설정된다")
        void allArgsConstructor_WithNullTimestamps_SetsCurrentTime() {
            // when
            User user = new User(1L, "test@example.com", "hash", UserRole.USER, null, null);

            // then
            assertThat(user.getCreatedAt()).isNotNull();
            assertThat(user.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("정적 팩토리 메서드 테스트")
    class FactoryMethodTest {

        @Test
        @DisplayName("create() 메서드로 유효한 User를 생성한다")
        void create_WithValidData_CreatesUser() {
            // given
            String email = "test@example.com";
            String passwordHash = "hashedPassword123";

            // when
            User user = User.create(email, passwordHash);

            // then
            assertThat(user.getEmail()).isEqualTo(email);
            assertThat(user.getPasswordHash()).isEqualTo(passwordHash);
            assertThat(user.getRole()).isEqualTo(UserRole.USER);
            assertThat(user.getCreatedAt()).isNotNull();
            assertThat(user.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("createWithRole() 메서드로 특정 역할을 가진 User를 생성한다")
        void createWithRole_CreatesUserWithSpecificRole() {
            // given
            String email = "admin@example.com";
            String passwordHash = "hashedPassword123";
            UserRole role = UserRole.ADMIN;

            // when
            User user = User.createWithRole(email, passwordHash, role);

            // then
            assertThat(user.getEmail()).isEqualTo(email);
            assertThat(user.getPasswordHash()).isEqualTo(passwordHash);
            assertThat(user.getRole()).isEqualTo(role);
        }

        @Test
        @DisplayName("create() 메서드는 이메일 검증을 수행한다")
        void create_ValidatesEmail() {
            // given
            String invalidEmail = "invalid-email";
            String passwordHash = "hashedPassword123";

            // when & then
            assertThatThrownBy(() -> User.create(invalidEmail, passwordHash))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid email format");
        }

        @Test
        @DisplayName("create() 메서드는 비밀번호 검증을 수행한다")
        void create_ValidatesPassword() {
            // given
            String email = "test@example.com";
            String emptyPasswordHash = "";

            // when & then
            assertThatThrownBy(() -> User.create(email, emptyPasswordHash))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Password hash cannot be empty");
        }
    }

    @Nested
    @DisplayName("이메일 검증 테스트")
    class EmailValidationTest {

        @Test
        @DisplayName("유효한 이메일은 검증을 통과한다")
        void validateEmail_WithValidEmail_Passes() {
            // given
            User user = new User();
            user.setEmail("test@example.com");

            // when & then
            assertThatCode(() -> user.validateEmail())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null 이메일은 예외를 발생시킨다")
        void validateEmail_WithNullEmail_ThrowsException() {
            // given
            User user = new User();
            user.setEmail(null);

            // when & then
            assertThatThrownBy(() -> user.validateEmail())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email cannot be empty");
        }

        @Test
        @DisplayName("빈 문자열 이메일은 예외를 발생시킨다")
        void validateEmail_WithEmptyEmail_ThrowsException() {
            // given
            User user = new User();
            user.setEmail("");

            // when & then
            assertThatThrownBy(() -> user.validateEmail())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email cannot be empty");
        }

        @Test
        @DisplayName("공백 이메일은 예외를 발생시킨다")
        void validateEmail_WithBlankEmail_ThrowsException() {
            // given
            User user = new User();
            user.setEmail("   ");

            // when & then
            assertThatThrownBy(() -> user.validateEmail())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email cannot be empty");
        }

        @Test
        @DisplayName("잘못된 형식의 이메일은 예외를 발생시킨다")
        void validateEmail_WithInvalidFormat_ThrowsException() {
            // given
            User user = new User();

            // when & then
            assertThatThrownBy(() -> {
                user.setEmail("invalid-email");
                user.validateEmail();
            }).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid email format");

            assertThatThrownBy(() -> {
                user.setEmail("@example.com");
                user.validateEmail();
            }).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> {
                user.setEmail("test@");
                user.validateEmail();
            }).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> {
                user.setEmail("test@.com");
                user.validateEmail();
            }).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("다양한 유효한 이메일 형식을 허용한다")
        void validateEmail_AcceptsVariousValidFormats() {
            // given
            User user = new User();
            String[] validEmails = {
                    "test@example.com",
                    "user.name@example.com",
                    "user+tag@example.co.kr",
                    "test123@test-domain.com",
                    "a@b.co"
            };

            // when & then
            for (String email : validEmails) {
                user.setEmail(email);
                assertThatCode(() -> user.validateEmail())
                        .as("Email: " + email)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("비밀번호 검증 테스트")
    class PasswordValidationTest {

        @Test
        @DisplayName("유효한 비밀번호 해시는 검증을 통과한다")
        void validatePasswordHash_WithValidHash_Passes() {
            // given
            User user = new User();
            user.setPasswordHash("hashedPassword123");

            // when & then
            assertThatCode(() -> user.validatePasswordHash())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null 비밀번호 해시는 예외를 발생시킨다")
        void validatePasswordHash_WithNull_ThrowsException() {
            // given
            User user = new User();
            user.setPasswordHash(null);

            // when & then
            assertThatThrownBy(() -> user.validatePasswordHash())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Password hash cannot be empty");
        }

        @Test
        @DisplayName("빈 문자열 비밀번호 해시는 예외를 발생시킨다")
        void validatePasswordHash_WithEmptyString_ThrowsException() {
            // given
            User user = new User();
            user.setPasswordHash("");

            // when & then
            assertThatThrownBy(() -> user.validatePasswordHash())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Password hash cannot be empty");
        }

        @Test
        @DisplayName("공백 비밀번호 해시는 예외를 발생시킨다")
        void validatePasswordHash_WithBlankString_ThrowsException() {
            // given
            User user = new User();
            user.setPasswordHash("   ");

            // when & then
            assertThatThrownBy(() -> user.validatePasswordHash())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Password hash cannot be empty");
        }
    }

    @Nested
    @DisplayName("비즈니스 메서드 테스트")
    class BusinessMethodTest {

        @Test
        @DisplayName("changeRole()은 역할을 변경하고 updatedAt을 갱신한다")
        void changeRole_ChangesRoleAndUpdatesTimestamp() {
            // given
            User user = new User();
            LocalDateTime beforeUpdate = user.getUpdatedAt();
            UserRole newRole = UserRole.ADMIN;

            // when
            // 시간 차이를 만들기 위해 짧은 대기
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            user.changeRole(newRole);

            // then
            assertThat(user.getRole()).isEqualTo(newRole);
            assertThat(user.getUpdatedAt()).isAfter(beforeUpdate);
        }

        @Test
        @DisplayName("updatePassword()는 비밀번호를 변경하고 updatedAt을 갱신한다")
        void updatePassword_ChangesPasswordAndUpdatesTimestamp() {
            // given
            User user = User.create("test@example.com", "oldHash");
            LocalDateTime beforeUpdate = user.getUpdatedAt();
            String newPasswordHash = "newHashedPassword";

            // when
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            user.updatePassword(newPasswordHash);

            // then
            assertThat(user.getPasswordHash()).isEqualTo(newPasswordHash);
            assertThat(user.getUpdatedAt()).isAfter(beforeUpdate);
        }

        @Test
        @DisplayName("isAdmin()은 ADMIN 역할일 때 true를 반환한다")
        void isAdmin_WithAdminRole_ReturnsTrue() {
            // given
            User user = User.createWithRole("admin@example.com", "hash", UserRole.ADMIN);

            // when & then
            assertThat(user.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("isAdmin()은 USER 역할일 때 false를 반환한다")
        void isAdmin_WithUserRole_ReturnsFalse() {
            // given
            User user = User.create("user@example.com", "hash");

            // when & then
            assertThat(user.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("isAdmin()은 MANAGER 역할일 때 false를 반환한다")
        void isAdmin_WithManagerRole_ReturnsFalse() {
            // given
            User user = User.createWithRole("manager@example.com", "hash", UserRole.MANAGER);

            // when & then
            assertThat(user.isAdmin()).isFalse();
        }
    }

    @Nested
    @DisplayName("통합 시나리오 테스트")
    class IntegrationScenarioTest {

        @Test
        @DisplayName("일반 사용자 생성 후 관리자로 승격하는 시나리오")
        void userPromotionScenario() {
            // given: 일반 사용자 생성
            String email = "user@example.com";
            String passwordHash = "hashedPassword";
            User user = User.create(email, passwordHash);

            // when: 관리자로 승격
            assertThat(user.isAdmin()).isFalse();
            user.changeRole(UserRole.ADMIN);

            // then: 관리자 권한 확인
            assertThat(user.isAdmin()).isTrue();
            assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        }

        @Test
        @DisplayName("비밀번호 변경 시나리오")
        void passwordChangeScenario() {
            // given
            User user = User.create("user@example.com", "oldHash");
            String oldHash = user.getPasswordHash();

            // when
            user.updatePassword("newHash");

            // then
            assertThat(user.getPasswordHash()).isNotEqualTo(oldHash);
            assertThat(user.getPasswordHash()).isEqualTo("newHash");
        }

        @Test
        @DisplayName("관리자 계정 생성 시나리오")
        void adminCreationScenario() {
            // when
            User admin = User.createWithRole(
                    "admin@company.com",
                    "secureHash123",
                    UserRole.ADMIN
            );

            // then
            assertThat(admin.getEmail()).isEqualTo("admin@company.com");
            assertThat(admin.isAdmin()).isTrue();
            assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        }
    }
}
