package github.lms.lemuel.user.application.port.in;

/**
 * 로그인 UseCase (Inbound Port)
 */
public interface LoginUseCase {

    LoginResult login(LoginCommand command);

    record LoginCommand(String email, String rawPassword) {
        public LoginCommand {
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("Email is required");
            }
            if (rawPassword == null || rawPassword.isBlank()) {
                throw new IllegalArgumentException("Password is required");
            }
        }
    }

    record LoginResult(String token, String email, String role) {}
}
