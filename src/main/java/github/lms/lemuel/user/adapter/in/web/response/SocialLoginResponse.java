package github.lms.lemuel.user.adapter.in.web.response;

import github.lms.lemuel.user.application.port.in.SocialLoginUseCase;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 소셜 로그인 응답 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SocialLoginResponse {

    private String token;
    private String email;
    private String role;
    private boolean isNewUser;

    public static SocialLoginResponse from(SocialLoginUseCase.LoginResult result) {
        return new SocialLoginResponse(
                result.token(),
                result.email(),
                result.role(),
                result.isNewUser()
        );
    }
}
