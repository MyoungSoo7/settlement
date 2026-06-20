package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.LoginUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock PasswordHashPort passwordHashPort;
    @Mock TokenProviderPort tokenProviderPort;
    @InjectMocks LoginService service;

    private User user() {
        return new User(1L, "u@example.com", "hashed", UserRole.USER, null, null);
    }

    @Test @DisplayName("정상 로그인 - 토큰 반환")
    void login_success() {
        when(loadUserPort.findByEmail("u@example.com")).thenReturn(Optional.of(user()));
        when(passwordHashPort.matches("raw", "hashed")).thenReturn(true);
        when(tokenProviderPort.generateToken("u@example.com", "USER")).thenReturn("jwt-token");

        LoginUseCase.LoginResult result = service.login(
                new LoginUseCase.LoginCommand("u@example.com", "raw"));

        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.email()).isEqualTo("u@example.com");
        assertThat(result.role()).isEqualTo("USER");
    }

    @Test @DisplayName("사용자 미존재 - InvalidCredentialsException")
    void login_userNotFound() {
        when(loadUserPort.findByEmail("x@y.z")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(
                new LoginUseCase.LoginCommand("x@y.z", "raw")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test @DisplayName("비밀번호 불일치 - InvalidCredentialsException")
    void login_wrongPassword() {
        when(loadUserPort.findByEmail("u@example.com")).thenReturn(Optional.of(user()));
        when(passwordHashPort.matches("bad", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                new LoginUseCase.LoginCommand("u@example.com", "bad")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test @DisplayName("Command 검증: email 공백")
    void command_blankEmail() {
        assertThatThrownBy(() -> new LoginUseCase.LoginCommand("  ", "pw"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("Command 검증: password 공백")
    void command_blankPassword() {
        assertThatThrownBy(() -> new LoginUseCase.LoginCommand("u@example.com", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
