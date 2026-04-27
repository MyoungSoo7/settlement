package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;

public interface CreateUserUseCase {

    User createUser(CreateUserCommand command);

    record CreateUserCommand(
            String email,
            String rawPassword,
            UserRole role
    ) {
        public CreateUserCommand {
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("Email cannot be empty");
            }
            if (rawPassword == null || rawPassword.isBlank()) {
                throw new IllegalArgumentException("Password cannot be empty");
            }
            if (role == null) {
                throw new IllegalArgumentException("Role cannot be null");
            }
        }
    }
}
