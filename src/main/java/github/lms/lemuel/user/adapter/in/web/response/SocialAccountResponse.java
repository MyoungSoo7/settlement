package github.lms.lemuel.user.adapter.in.web.response;

import github.lms.lemuel.user.domain.SocialAccount;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 소셜 계정 응답 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SocialAccountResponse {

    private Long id;
    private String provider;
    private String email;
    private String name;
    private String profileImage;
    private LocalDateTime createdAt;

    public static SocialAccountResponse from(SocialAccount account) {
        return new SocialAccountResponse(
                account.getId(),
                account.getProvider().name(),
                account.getEmail(),
                account.getName(),
                account.getProfileImage(),
                account.getCreatedAt()
        );
    }
}
