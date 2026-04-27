package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;

/**
 * 회원 수정 UseCase (Inbound Port)
 */
public interface UpdateUserUseCase {

    User changeUserRole(Long userId, UserRole newRole);

    void suspendUser(Long userId);

    void activateUser(Long userId);
}
