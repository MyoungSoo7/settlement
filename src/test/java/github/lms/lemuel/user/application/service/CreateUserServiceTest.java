package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.CreateUserUseCase.CreateUserCommand;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.DuplicateEmailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * CreateUserService TDD Test
 *
 * 테스트 범위:
 * 1. 정상적인 회원가입 흐름
 * 2. 이메일 중복 검증
 * 3. 비밀번호 해싱
 * 4. 도메인 검증 통합
 * 5. 역할별 사용자 생성
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateUserService 애플리케이션 서비스")
class CreateUserServiceTest {

    @Mock
    private LoadUserPort loadUserPort;

    @Mock
    private SaveUserPort saveUserPort;

    @Mock
    private PasswordHashPort passwordHashPort;

    @InjectMocks
    private CreateUserService createUserService;

    @Nested
    @DisplayName("회원가입 성공 시나리오")
    class SuccessScenario {

        @Test
        @DisplayName("유효한 정보로 일반 사용자를 생성한다")
        void createUser_WithValidData_CreatesUser() {
            // given
            String email = "newuser@example.com";
            String rawPassword = "password123";
            String hashedPassword = "hashedPassword123";
            CreateUserCommand command = new CreateUserCommand(email, rawPassword, UserRole.USER);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());
            given(passwordHashPort.hash(rawPassword)).willReturn(hashedPassword);
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            // when
            User result = createUserService.createUser(command);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getEmail()).isEqualTo(email);
            assertThat(result.getPasswordHash()).isEqualTo(hashedPassword);
            assertThat(result.getRole()).isEqualTo(UserRole.USER);

            // verify interactions
            then(loadUserPort).should().findByEmail(email);
            then(passwordHashPort).should().hash(rawPassword);
            then(saveUserPort).should().save(any(User.class));
        }

        @Test
        @DisplayName("관리자 역할을 가진 사용자를 생성한다")
        void createUser_WithAdminRole_CreatesAdminUser() {
            // given
            String email = "admin@example.com";
            String rawPassword = "adminpass";
            String hashedPassword = "hashedAdminPass";
            CreateUserCommand command = new CreateUserCommand(email, rawPassword, UserRole.ADMIN);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());
            given(passwordHashPort.hash(rawPassword)).willReturn(hashedPassword);
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(2L);
                return user;
            });

            // when
            User result = createUserService.createUser(command);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo(email);
            assertThat(result.getRole()).isEqualTo(UserRole.ADMIN);
            assertThat(result.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("매니저 역할을 가진 사용자를 생성한다")
        void createUser_WithManagerRole_CreatesManagerUser() {
            // given
            String email = "manager@example.com";
            String rawPassword = "managerpass";
            String hashedPassword = "hashedManagerPass";
            CreateUserCommand command = new CreateUserCommand(email, rawPassword, UserRole.MANAGER);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());
            given(passwordHashPort.hash(rawPassword)).willReturn(hashedPassword);
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(3L);
                return user;
            });

            // when
            User result = createUserService.createUser(command);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getRole()).isEqualTo(UserRole.MANAGER);
        }
    }

    @Nested
    @DisplayName("이메일 중복 검증")
    class EmailDuplicationValidation {

        @Test
        @DisplayName("이미 존재하는 이메일로 회원가입 시 DuplicateEmailException을 발생시킨다")
        void createUser_WithDuplicateEmail_ThrowsException() {
            // given
            String email = "existing@example.com";
            CreateUserCommand command = new CreateUserCommand(email, "password", UserRole.USER);

            User existingUser = User.create(email, "existingHash");
            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(existingUser));

            // when & then
            assertThatThrownBy(() -> createUserService.createUser(command))
                    .isInstanceOf(DuplicateEmailException.class);

            // verify
            then(loadUserPort).should().findByEmail(email);
            then(passwordHashPort).should(never()).hash(anyString());
            then(saveUserPort).should(never()).save(any(User.class));
        }

        @Test
        @DisplayName("중복되지 않은 이메일은 정상적으로 처리된다")
        void createUser_WithUniqueEmail_Succeeds() {
            // given
            String email = "unique@example.com";
            CreateUserCommand command = new CreateUserCommand(email, "password", UserRole.USER);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());
            given(passwordHashPort.hash(anyString())).willReturn("hashedPassword");
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            // when
            User result = createUserService.createUser(command);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo(email);
        }
    }

    @Nested
    @DisplayName("비밀번호 해싱")
    class PasswordHashing {

        @Test
        @DisplayName("원본 비밀번호를 해싱하여 저장한다")
        void createUser_HashesPassword() {
            // given
            String email = "test@example.com";
            String rawPassword = "plainPassword123";
            String hashedPassword = "$2a$10$hashedPasswordValue";
            CreateUserCommand command = new CreateUserCommand(email, rawPassword, UserRole.USER);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());
            given(passwordHashPort.hash(rawPassword)).willReturn(hashedPassword);
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            // when
            User result = createUserService.createUser(command);

            // then
            assertThat(result.getPasswordHash()).isEqualTo(hashedPassword);
            assertThat(result.getPasswordHash()).isNotEqualTo(rawPassword);

            // verify
            then(passwordHashPort).should().hash(rawPassword);
        }

        @Test
        @DisplayName("비밀번호 해싱 서비스를 정확히 한 번만 호출한다")
        void createUser_CallsHashingOnce() {
            // given
            String email = "test@example.com";
            CreateUserCommand command = new CreateUserCommand(email, "password", UserRole.USER);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());
            given(passwordHashPort.hash(anyString())).willReturn("hashed");
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            // when
            createUserService.createUser(command);

            // then
            then(passwordHashPort).should(times(1)).hash("password");
        }
    }

    @Nested
    @DisplayName("도메인 검증 통합")
    class DomainValidationIntegration {

        @Test
        @DisplayName("잘못된 이메일 형식은 도메인에서 예외를 발생시킨다")
        void createUser_WithInvalidEmailFormat_ThrowsException() {
            // given
            String invalidEmail = "invalid-email-format";
            CreateUserCommand command = new CreateUserCommand(invalidEmail, "password", UserRole.USER);

            given(loadUserPort.findByEmail(invalidEmail)).willReturn(Optional.empty());
            given(passwordHashPort.hash(anyString())).willReturn("hashed");

            // when & then
            assertThatThrownBy(() -> createUserService.createUser(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid email format");

            // verify - save should not be called due to validation failure
            then(saveUserPort).should(never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("저장 로직")
    class SaveLogic {

        @Test
        @DisplayName("생성된 사용자를 포트를 통해 저장한다")
        void createUser_SavesUserThroughPort() {
            // given
            String email = "save@example.com";
            CreateUserCommand command = new CreateUserCommand(email, "password", UserRole.USER);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());
            given(passwordHashPort.hash(anyString())).willReturn("hashed");
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(100L);
                return user;
            });

            // when
            User result = createUserService.createUser(command);

            // then
            assertThat(result.getId()).isEqualTo(100L);
            then(saveUserPort).should().save(argThat(user ->
                    user.getEmail().equals(email) &&
                            user.getPasswordHash().equals("hashed") &&
                            user.getRole() == UserRole.USER
            ));
        }

        @Test
        @DisplayName("저장 후 ID가 할당된 사용자를 반환한다")
        void createUser_ReturnsUserWithAssignedId() {
            // given
            CreateUserCommand command = new CreateUserCommand("test@example.com", "pass", UserRole.USER);

            given(loadUserPort.findByEmail(anyString())).willReturn(Optional.empty());
            given(passwordHashPort.hash(anyString())).willReturn("hashed");
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(999L);
                return user;
            });

            // when
            User result = createUserService.createUser(command);

            // then
            assertThat(result.getId()).isNotNull();
            assertThat(result.getId()).isEqualTo(999L);
        }
    }

    @Nested
    @DisplayName("Command 검증")
    class CommandValidation {

        @Test
        @DisplayName("빈 이메일로 Command 생성 시 예외가 발생한다")
        void createCommand_WithEmptyEmail_ThrowsException() {
            // when & then
            assertThatThrownBy(() -> new CreateUserCommand("", "password", UserRole.USER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email cannot be empty");
        }

        @Test
        @DisplayName("null 이메일로 Command 생성 시 예외가 발생한다")
        void createCommand_WithNullEmail_ThrowsException() {
            // when & then
            assertThatThrownBy(() -> new CreateUserCommand(null, "password", UserRole.USER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email cannot be empty");
        }

        @Test
        @DisplayName("빈 비밀번호로 Command 생성 시 예외가 발생한다")
        void createCommand_WithEmptyPassword_ThrowsException() {
            // when & then
            assertThatThrownBy(() -> new CreateUserCommand("test@example.com", "", UserRole.USER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Password cannot be empty");
        }

        @Test
        @DisplayName("null 역할로 Command 생성 시 예외가 발생한다")
        void createCommand_WithNullRole_ThrowsException() {
            // when & then
            assertThatThrownBy(() -> new CreateUserCommand("test@example.com", "password", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Role cannot be null");
        }
    }

    @Nested
    @DisplayName("전체 통합 시나리오")
    class FullIntegrationScenario {

        @Test
        @DisplayName("회원가입 전체 플로우: 중복확인 → 해싱 → 도메인생성 → 저장")
        void createUser_FullFlow_Success() {
            // given
            String email = "fullflow@example.com";
            String rawPassword = "mySecurePassword";
            String hashedPassword = "$2a$10$fullFlowHash";
            CreateUserCommand command = new CreateUserCommand(email, rawPassword, UserRole.USER);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());
            given(passwordHashPort.hash(rawPassword)).willReturn(hashedPassword);
            given(saveUserPort.save(any(User.class))).willAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(42L);
                return user;
            });

            // when
            User result = createUserService.createUser(command);

            // then - 모든 단계 검증
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getEmail()).isEqualTo(email);
            assertThat(result.getPasswordHash()).isEqualTo(hashedPassword);
            assertThat(result.getRole()).isEqualTo(UserRole.USER);
            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();

            // verify - 올바른 순서로 호출되었는지 확인
            var inOrder = inOrder(loadUserPort, passwordHashPort, saveUserPort);
            inOrder.verify(loadUserPort).findByEmail(email);
            inOrder.verify(passwordHashPort).hash(rawPassword);
            inOrder.verify(saveUserPort).save(any(User.class));
        }
    }
}
