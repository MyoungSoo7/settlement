package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.CreateUserUseCase;
import github.lms.lemuel.user.application.port.in.CreateUserUseCase.CreateUserCommand;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.DuplicateEmailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateUserServiceTest {

    @Mock
    private LoadUserPort loadUserPort;

    @Mock
    private SaveUserPort saveUserPort;

    @Mock
    private PasswordHashPort passwordHashPort;

    private CreateUserService createUserService;

    @BeforeEach
    void setUp() {
        createUserService = new CreateUserService(
                loadUserPort,
                saveUserPort,
                passwordHashPort
        );
    }

    @Test
    @DisplayName("성공: 새로운 사용자 회원가입")
    void testCreateUser_Success() {
        String email = "test@example.com";
        String rawPassword = "password123";
        String hashedPassword = "$2a$10$hashedPassword";
        UserRole role = UserRole.USER;

        CreateUserCommand command = new CreateUserCommand(email, rawPassword, role);

        when(loadUserPort.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordHashPort.hash(rawPassword)).thenReturn(hashedPassword);

        User savedUser = User.createWithRole(email, hashedPassword, role);
        savedUser.setId(1L);
        when(saveUserPort.save(any(User.class))).thenReturn(savedUser);

        User result = createUserService.createUser(command);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getEmail()).isEqualTo(email);
        assertThat(result.getPasswordHash()).isEqualTo(hashedPassword);
        assertThat(result.getRole()).isEqualTo(role);

        verify(loadUserPort, times(1)).findByEmail(email);
        verify(passwordHashPort, times(1)).hash(rawPassword);
        verify(saveUserPort, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("실패: 중복 이메일로 회원가입 시도")
    void testCreateUser_DuplicateEmail() {
        String email = "duplicate@example.com";
        String rawPassword = "password123";
        UserRole role = UserRole.USER;

        CreateUserCommand command = new CreateUserCommand(email, rawPassword, role);

        User existingUser = User.create(email, "existingHash");
        existingUser.setId(999L);
        when(loadUserPort.findByEmail(email)).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> createUserService.createUser(command))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining("이미 존재하는 이메일입니다")
                .hasMessageContaining(email);

        verify(loadUserPort, times(1)).findByEmail(email);
        verify(passwordHashPort, never()).hash(anyString());
        verify(saveUserPort, never()).save(any(User.class));
    }
}
