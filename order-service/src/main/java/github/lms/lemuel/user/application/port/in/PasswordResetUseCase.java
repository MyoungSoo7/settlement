package github.lms.lemuel.user.application.port.in;

public interface PasswordResetUseCase {

    void requestPasswordReset(String email);

    void resetPassword(ResetPasswordCommand command);

    record ResetPasswordCommand(
            String token,
            String newPassword
    ) {
        public ResetPasswordCommand {
            if (token == null || token.trim().isEmpty()) {
                throw new IllegalArgumentException("Token cannot be empty");
            }
            if (newPassword == null || newPassword.length() < 8) {
                throw new IllegalArgumentException("Password must be at least 8 characters");
            }
        }
    }
}
