package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.LoginUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 서비스
 */
@Service
@RequiredArgsConstructor
public class LoginService implements LoginUseCase {

    private final LoadUserPort loadUserPort;
    private final PasswordHashPort passwordHashPort;
    private final TokenProviderPort tokenProviderPort;

    @Override
    @Transactional(readOnly = true)
    public LoginResult login(LoginCommand command) {
        // 사용자 조회
        User user = loadUserPort.findByEmail(command.email())
                .orElseThrow(InvalidCredentialsException::new);

        // 비밀번호 검증
        if (!passwordHashPort.matches(command.rawPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // 토큰 생성
        String token = tokenProviderPort.generateToken(user.getEmail(), user.getRole().name());

        return new LoginResult(token, user.getEmail(), user.getRole().name());
    }
}
