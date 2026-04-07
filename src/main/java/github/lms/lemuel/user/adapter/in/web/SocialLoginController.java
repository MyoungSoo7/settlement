package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.user.adapter.in.web.request.SocialLinkRequest;
import github.lms.lemuel.user.adapter.in.web.request.SocialLoginRequest;
import github.lms.lemuel.user.adapter.in.web.request.SocialUnlinkRequest;
import github.lms.lemuel.user.adapter.in.web.response.SocialAccountResponse;
import github.lms.lemuel.user.adapter.in.web.response.SocialLoginResponse;
import github.lms.lemuel.user.application.port.in.SocialLoginUseCase;
import github.lms.lemuel.user.domain.SocialAccount;
import github.lms.lemuel.user.domain.SocialProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Social Login API Controller
 */
@RestController
@RequestMapping("/api/auth/social")
@RequiredArgsConstructor
public class SocialLoginController {

    private final SocialLoginUseCase socialLoginUseCase;

    /**
     * 소셜 로그인
     * POST /api/auth/social/login
     */
    @PostMapping("/login")
    public ResponseEntity<SocialLoginResponse> socialLogin(@Valid @RequestBody SocialLoginRequest request) {
        SocialLoginUseCase.SocialLoginCommand command = new SocialLoginUseCase.SocialLoginCommand(
                SocialProvider.fromString(request.getProvider()),
                request.getCode(),
                request.getRedirectUri()
        );
        SocialLoginUseCase.LoginResult result = socialLoginUseCase.socialLogin(command);
        return ResponseEntity.ok(SocialLoginResponse.from(result));
    }

    /**
     * 소셜 계정 연동
     * POST /api/auth/social/link
     */
    @PostMapping("/link")
    public ResponseEntity<SocialAccountResponse> linkSocialAccount(@Valid @RequestBody SocialLinkRequest request) {
        SocialLoginUseCase.SocialLoginCommand command = new SocialLoginUseCase.SocialLoginCommand(
                SocialProvider.fromString(request.getProvider()),
                request.getCode(),
                request.getRedirectUri()
        );
        SocialAccount account = socialLoginUseCase.linkSocialAccount(request.getUserId(), command);
        return ResponseEntity.ok(SocialAccountResponse.from(account));
    }

    /**
     * 소셜 계정 연동 해제
     * DELETE /api/auth/social/unlink
     */
    @DeleteMapping("/unlink")
    public ResponseEntity<Void> unlinkSocialAccount(@Valid @RequestBody SocialUnlinkRequest request) {
        socialLoginUseCase.unlinkSocialAccount(
                request.getUserId(),
                SocialProvider.fromString(request.getProvider())
        );
        return ResponseEntity.noContent().build();
    }

    /**
     * 사용자의 소셜 계정 목록 조회
     * GET /api/auth/social/{userId}/accounts
     */
    @GetMapping("/{userId}/accounts")
    public ResponseEntity<List<SocialAccountResponse>> getSocialAccounts(@PathVariable Long userId) {
        List<SocialAccount> accounts = socialLoginUseCase.getSocialAccounts(userId);
        List<SocialAccountResponse> response = accounts.stream()
                .map(SocialAccountResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
}
