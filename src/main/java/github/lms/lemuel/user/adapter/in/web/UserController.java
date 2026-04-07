package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.user.adapter.in.web.request.CreateUserRequest;
import github.lms.lemuel.user.adapter.in.web.request.PasswordResetRequestDto;
import github.lms.lemuel.user.adapter.in.web.request.ResetPasswordDto;
import github.lms.lemuel.user.adapter.in.web.response.UserResponse;
import github.lms.lemuel.user.application.port.in.CreateUserUseCase;
import github.lms.lemuel.user.application.port.in.GetUserUseCase;
import github.lms.lemuel.user.application.port.in.PasswordResetUseCase;
import github.lms.lemuel.user.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User API Controller
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final CreateUserUseCase createUserUseCase;
    private final GetUserUseCase getUserUseCase;
    private final PasswordResetUseCase passwordResetUseCase;

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = createUserUseCase.createUser(
                new CreateUserUseCase.CreateUserCommand(request.getEmail(), request.getPassword(), request.getRole())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable Long id) {
        User user = getUserUseCase.getUserById(id);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @GetMapping("/admin/all")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<UserResponse> users = getUserUseCase.getAllUsers()
                .stream()
                .map(UserResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    /**
     * 비밀번호 재설정 요청 (이메일 발송)
     * POST /users/password-reset/request
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequestDto request) {
        passwordResetUseCase.requestPasswordReset(request.email());
        return ResponseEntity.ok(Map.of(
                "message", "비밀번호 재설정 이메일이 발송되었습니다.",
                "email", request.email()
        ));
    }

    /**
     * 비밀번호 재설정 (토큰 검증 후 비밀번호 변경)
     * POST /users/password-reset/confirm
     */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordDto request) {
        passwordResetUseCase.resetPassword(
                new PasswordResetUseCase.ResetPasswordCommand(request.token(), request.newPassword())
        );
        return ResponseEntity.ok(Map.of(
                "message", "비밀번호가 성공적으로 변경되었습니다."
        ));
    }
}
