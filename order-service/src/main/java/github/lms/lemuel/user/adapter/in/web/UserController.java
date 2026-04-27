package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.user.adapter.in.web.request.CreateUserRequest;
import github.lms.lemuel.user.adapter.in.web.request.PasswordResetRequestDto;
import github.lms.lemuel.user.adapter.in.web.request.ResetPasswordDto;
import github.lms.lemuel.user.adapter.in.web.response.UserResponse;
import github.lms.lemuel.user.application.port.in.CreateUserUseCase;
import github.lms.lemuel.user.application.port.in.GetUserUseCase;
import github.lms.lemuel.user.application.port.in.PasswordResetUseCase;
import github.lms.lemuel.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "User", description = "회원 가입/조회/비밀번호 재설정 API")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final CreateUserUseCase createUserUseCase;
    private final GetUserUseCase getUserUseCase;
    private final PasswordResetUseCase passwordResetUseCase;

    @Operation(summary = "회원 가입", description = "이메일/비밀번호/역할로 회원을 생성한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "가입 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "409", description = "이메일 중복")
    })
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = createUserUseCase.createUser(
                new CreateUserUseCase.CreateUserCommand(request.getEmail(), request.getPassword(), request.getRole())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @Operation(summary = "회원 단건 조회", description = "ID로 회원을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(
            @Parameter(description = "회원 ID", required = true) @PathVariable Long id) {
        User user = getUserUseCase.getUserById(id);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @Operation(summary = "전체 회원 조회 (관리자)", description = "시스템의 모든 회원을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
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
    @Operation(summary = "비밀번호 재설정 요청",
            description = "지정한 이메일로 비밀번호 재설정 링크(토큰)를 발송한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재설정 메일 발송"),
            @ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음")
    })
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
    @Operation(summary = "비밀번호 재설정 확정",
            description = "재설정 토큰과 새 비밀번호로 비밀번호를 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공"),
            @ApiResponse(responseCode = "400", description = "토큰 만료 또는 무효")
    })
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
