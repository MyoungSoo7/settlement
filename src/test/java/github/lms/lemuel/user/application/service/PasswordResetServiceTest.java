package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.out.*;
import github.lms.lemuel.user.domain.PasswordResetToken;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.InvalidPasswordResetTokenException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock SaveUserPort saveUserPort;
    @Mock PasswordHashPort passwordHashPort;
    @Mock SavePasswordResetTokenPort savePasswordResetTokenPort;
    @Mock SendEmailPort sendEmailPort;
    @InjectMocks PasswordResetService passwordResetService;

    @Test @DisplayName("비밀번호 재설정 요청 - 존재하지 않는 이메일이면 조용히 무시")
    void requestReset_unknownEmail_silentlyIgnored() {
        when(loadUserPort.loadByEmail("unknown@test.com")).thenReturn(Optional.empty());

        // 예외 없이 조용히 처리되어야 함 (보안: 이메일 존재 여부 노출 방지)
        assertThatCode(() -> passwordResetService.requestPasswordReset("unknown@test.com"))
                .doesNotThrowAnyException();
    }
}
