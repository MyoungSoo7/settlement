package github.lms.lemuel.menu.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "메뉴 생성 요청")
public class MenuCreateRequest {

    @NotBlank(message = "메뉴 이름은 필수입니다.")
    @Size(max = 100, message = "메뉴 이름은 100자 이하여야 합니다.")
    @Schema(description = "메뉴 이름", example = "사용자 관리")
    private String name;

    @Size(max = 255, message = "경로는 255자 이하여야 합니다.")
    @Schema(description = "메뉴 경로", example = "/admin/users")
    private String path;

    @Size(max = 50, message = "아이콘은 50자 이하여야 합니다.")
    @Schema(description = "아이콘", example = "👤")
    private String icon;

    @Schema(description = "부모 메뉴 ID (최상위이면 null)", example = "1")
    private Long parentId;

    @Schema(description = "정렬 순서", example = "0")
    private int sortOrder;

    @Size(max = 20, message = "권한은 20자 이하여야 합니다.")
    @Schema(description = "접근 필요 권한", example = "ADMIN")
    private String requiredRole;

    @Schema(description = "노출 여부", example = "true")
    private boolean visible = true;
}
