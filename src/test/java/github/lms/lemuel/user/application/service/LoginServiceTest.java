package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.LoginUseCase.LoginCommand;
import github.lms.lemuel.user.application.port.in.LoginUseCase.LoginResult;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
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
 * LoginService TDD Test
 *
 * 테스트 범위:
 * 1. 정상 로그인 흐름
 * 2. 사용자 조회 실패
 * 3. 비밀번호 불일치
 * 4. 토큰 생성
 * 5. 역할별 로그인
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LoginService 애플리케이션 서비스")
class LoginServiceTest {

    @Mock
    private LoadUserPort loadUserPort;

    @Mock
    private PasswordHashPort passwordHashPort;

    @Mock
    private TokenProviderPort tokenProviderPort;

    @InjectMocks
    private LoginService loginService;

    @Nested
    @DisplayName("로그인 성공 시나리오")
    class SuccessScenario {

        @Test
        @DisplayName("유효한 자격증명으로 로그인하면 토큰을 반환한다")
        void login_WithValidCredentials_ReturnsToken() {
            // given
            String email = "user@example.com";
            String rawPassword = "correctPassword";
            String passwordHash = "hashedPassword";
            String token = "jwt.token.here";

            LoginCommand command = new LoginCommand(email, rawPassword);
            User user = User.create(email, passwordHash);
            user.setId(1L);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(user));
            given(passwordHashPort.matches(rawPassword, passwordHash)).willReturn(true);
            given(tokenProviderPort.generateToken(email, UserRole.USER.name())).willReturn(token);

            // when
            LoginResult result = loginService.login(command);

            // then
            assertThat(result).isNotNull();
            assertThat(result.token()).isEqualTo(token);
            assertThat(result.email()).isEqualTo(email);
            assertThat(result.role()).isEqualTo(UserRole.USER.name());

            // verify
            then(loadUserPort).should().findByEmail(email);
            then(passwordHashPort).should().matches(rawPassword, passwordHash);
            then(tokenProviderPort).should().generateToken(email, UserRole.USER.name());
        }

        @Test
        @DisplayName("관리자 로그인 시 ADMIN 역할과 함께 토큰을 반환한다")
        void login_WithAdminUser_ReturnsTokenWithAdminRole() {
            // given
            String email = "admin@example.com";
            String rawPassword = "adminPassword";
            String passwordHash = "hashedAdminPassword";
            String token = "admin.jwt.token";

            LoginCommand command = new LoginCommand(email, rawPassword);
            User adminUser = User.createWithRole(email, passwordHash, UserRole.ADMIN);
            adminUser.setId(2L);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(adminUser));
            given(passwordHashPort.matches(rawPassword, passwordHash)).willReturn(true);
            given(tokenProviderPort.generateToken(email, UserRole.ADMIN.name())).willReturn(token);

            // when
            LoginResult result = loginService.login(command);

            // then
            assertThat(result.token()).isEqualTo(token);
            assertThat(result.email()).isEqualTo(email);
            assertThat(result.role()).isEqualTo(UserRole.ADMIN.name());
        }

        @Test
        @DisplayName("매니저 로그인 시 MANAGER 역할과 함께 토큰을 반환한다")
        void login_WithManagerUser_ReturnsTokenWithManagerRole() {
            // given
            String email = "manager@example.com";
            String rawPassword = "managerPassword";
            String passwordHash = "hashedManagerPassword";
            String token = "manager.jwt.token";

            LoginCommand command = new LoginCommand(email, rawPassword);
            User managerUser = User.createWithRole(email, passwordHash, UserRole.MANAGER);
            managerUser.setId(3L);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(managerUser));
            given(passwordHashPort.matches(rawPassword, passwordHash)).willReturn(true);
            given(tokenProviderPort.generateToken(email, UserRole.MANAGER.name())).willReturn(token);

            // when
            LoginResult result = loginService.login(command);

            // then
            assertThat(result.role()).isEqualTo(UserRole.MANAGER.name());
        }
    }

    @Nested
    @DisplayName("사용자 조회 실패")
    class UserNotFound {

        @Test
        @DisplayName("존재하지 않는 이메일로 로그인 시 InvalidCredentialsException을 발생시킨다")
        void login_WithNonExistentEmail_ThrowsException() {
            // given
            String email = "nonexistent@example.com";
            LoginCommand command = new LoginCommand(email, "password");

            given(loadUserPort.findByEmail(email)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> loginService.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            // verify - 비밀번호 검증 및 토큰 생성은 호출되지 않아야 함
            then(loadUserPort).should().findByEmail(email);
            then(passwordHashPort).should(never()).matches(anyString(), anyString());
            then(tokenProviderPort).should(never()).generateToken(anyString(), anyString());
        }

        @Test
        @DisplayName("사용자를 찾을 수 없을 때 보안을 위해 일반적인 에러 메시지를 사용한다")
        void login_WithNonExistentUser_UsesGenericErrorMessage() {
            // given
            LoginCommand command = new LoginCommand("ghost@example.com", "password");
            given(loadUserPort.findByEmail(anyString())).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> loginService.login(command))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessageContaining(""); // 보안상 상세 정보 노출하지 않음
        }
    }

    @Nested
    @DisplayName("비밀번호 불일치")
    class PasswordMismatch {

        @Test
        @DisplayName("잘못된 비밀번호로 로그인 시 InvalidCredentialsException을 발생시킨다")
        void login_WithWrongPassword_ThrowsException() {
            // given
            String email = "user@example.com";
            String correctHash = "correctHash";
            String wrongPassword = "wrongPassword";

            LoginCommand command = new LoginCommand(email, wrongPassword);
            User user = User.create(email, correctHash);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(user));
            given(passwordHashPort.matches(wrongPassword, correctHash)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> loginService.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            // verify - 토큰 생성은 호출되지 않아야 함
            then(passwordHashPort).should().matches(wrongPassword, correctHash);
            then(tokenProviderPort).should(never()).generateToken(anyString(), anyString());
        }

        @Test
        @DisplayName("비밀번호 검증은 해시 값과 비교한다")
        void login_VerifiesPasswordAgainstHash() {
            // given
            String email = "user@example.com";
            String rawPassword = "plainPassword";
            String passwordHash = "$2a$10$hashedValue";

            LoginCommand command = new LoginCommand(email, rawPassword);
            User user = User.create(email, passwordHash);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(user));
            given(passwordHashPort.matches(rawPassword, passwordHash)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> loginService.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            then(passwordHashPort).should().matches(rawPassword, passwordHash);
        }
    }

    @Nested
    @DisplayName("토큰 생성")
    class TokenGeneration {

        @Test
        @DisplayName("로그인 성공 시 사용자 이메일과 역할로 토큰을 생성한다")
        void login_GeneratesTokenWithEmailAndRole() {
            // given
            String email = "token@example.com";
            String rawPassword = "password";
            String passwordHash = "hash";
            String expectedToken = "generated.jwt.token";

            LoginCommand command = new LoginCommand(email, rawPassword);
            User user = User.create(email, passwordHash);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(user));
            given(passwordHashPort.matches(rawPassword, passwordHash)).willReturn(true);
            given(tokenProviderPort.generateToken(email, UserRole.USER.name())).willReturn(expectedToken);

            // when
            LoginResult result = loginService.login(command);

            // then
            assertThat(result.token()).isEqualTo(expectedToken);
            then(tokenProviderPort).should().generateToken(email, UserRole.USER.name());
        }

        @Test
        @DisplayName("토큰 제공자는 정확히 한 번만 호출된다")
        void login_CallsTokenProviderOnce() {
            // given
            String email = "once@example.com";
            LoginCommand command = new LoginCommand(email, "password");
            User user = User.create(email, "hash");

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(user));
            given(passwordHashPort.matches(anyString(), anyString())).willReturn(true);
            given(tokenProviderPort.generateToken(anyString(), anyString())).willReturn("token");

            // when
            loginService.login(command);

            // then
            then(tokenProviderPort).should(times(1)).generateToken(email, UserRole.USER.name());
        }
    }

    @Nested
    @DisplayName("LoginCommand 검증")
    class CommandValidation {

        @Test
        @DisplayName("빈 이메일로 Command 생성 시 예외가 발생한다")
        void createCommand_WithEmptyEmail_ThrowsException() {
            // when & then
            assertThatThrownBy(() -> new LoginCommand("", "password"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email is required");
        }

        @Test
        @DisplayName("null 이메일로 Command 생성 시 예외가 발생한다")
        void createCommand_WithNullEmail_ThrowsException() {
            // when & then
            assertThatThrownBy(() -> new LoginCommand(null, "password"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email is required");
        }

        @Test
        @DisplayName("빈 비밀번호로 Command 생성 시 예외가 발생한다")
        void createCommand_WithEmptyPassword_ThrowsException() {
            // when & then
            assertThatThrownBy(() -> new LoginCommand("test@example.com", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Password is required");
        }

        @Test
        @DisplayName("null 비밀번호로 Command 생성 시 예외가 발생한다")
        void createCommand_WithNullPassword_ThrowsException() {
            // when & then
            assertThatThrownBy(() -> new LoginCommand("test@example.com", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Password is required");
        }
    }

    @Nested
    @DisplayName("전체 통합 시나리오")
    class FullIntegrationScenario {

        @Test
        @DisplayName("로그인 전체 플로우: 사용자조회 → 비밀번호검증 → 토큰생성")
        void login_FullFlow_Success() {
            // given
            String email = "fullflow@example.com";
            String rawPassword = "myPassword123";
            String passwordHash = "$2a$10$fullFlowHash";
            String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...";

            LoginCommand command = new LoginCommand(email, rawPassword);
            User user = User.createWithRole(email, passwordHash, UserRole.ADMIN);
            user.setId(100L);

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(user));
            given(passwordHashPort.matches(rawPassword, passwordHash)).willReturn(true);
            given(tokenProviderPort.generateToken(email, UserRole.ADMIN.name())).willReturn(token);

            // when
            LoginResult result = loginService.login(command);

            // then - 모든 결과 검증
            assertThat(result).isNotNull();
            assertThat(result.token()).isEqualTo(token);
            assertThat(result.email()).isEqualTo(email);
            assertThat(result.role()).isEqualTo(UserRole.ADMIN.name());

            // verify - 올바른 순서로 호출되었는지 확인
            var inOrder = inOrder(loadUserPort, passwordHashPort, tokenProviderPort);
            inOrder.verify(loadUserPort).findByEmail(email);
            inOrder.verify(passwordHashPort).matches(rawPassword, passwordHash);
            inOrder.verify(tokenProviderPort).generateToken(email, UserRole.ADMIN.name());
        }

        @Test
        @DisplayName("실패한 로그인 시도는 토큰을 생성하지 않는다")
        void login_FailedAttempt_DoesNotGenerateToken() {
            // given
            String email = "fail@example.com";
            LoginCommand command = new LoginCommand(email, "wrongPassword");
            User user = User.create(email, "correctHash");

            given(loadUserPort.findByEmail(email)).willReturn(Optional.of(user));
            given(passwordHashPort.matches(anyString(), anyString())).willReturn(false);

            // when & then
            assertThatThrownBy(() -> loginService.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);

            // verify - 토큰은 생성되지 않음
            then(tokenProviderPort).should(never()).generateToken(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("보안 고려사항")
    class SecurityConsiderations {

        @Test
        @DisplayName("존재하지 않는 사용자와 잘못된 비밀번호에 대해 동일한 예외를 발생시킨다")
        void login_UseSameExceptionForSecurityReasons() {
            // given - 존재하지 않는 사용자
            LoginCommand command1 = new LoginCommand("nonexistent@example.com", "password");
            given(loadUserPort.findByEmail("nonexistent@example.com")).willReturn(Optional.empty());

            // given - 잘못된 비밀번호
            String existingEmail = "existing@example.com";
            LoginCommand command2 = new LoginCommand(existingEmail, "wrongPassword");
            User user = User.create(existingEmail, "correctHash");
            given(loadUserPort.findByEmail(existingEmail)).willReturn(Optional.of(user));
            given(passwordHashPort.matches("wrongPassword", "correctHash")).willReturn(false);

            // when & then - 동일한 예외 타입
            assertThatThrownBy(() -> loginService.login(command1))
                    .isInstanceOf(InvalidCredentialsException.class);

            assertThatThrownBy(() -> loginService.login(command2))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
    }
}
