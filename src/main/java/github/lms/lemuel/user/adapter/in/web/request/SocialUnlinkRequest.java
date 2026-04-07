package github.lms.lemuel.user.adapter.in.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 소셜 계정 연동 해제 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SocialUnlinkRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotBlank(message = "Provider is required")
    private String provider;
}
