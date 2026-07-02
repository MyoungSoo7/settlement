package github.lms.lemuel.rbac.adapter.in.web;

import github.lms.lemuel.rbac.adapter.in.web.dto.PermissionResponse;
import github.lms.lemuel.rbac.adapter.in.web.dto.RoleResponse;
import github.lms.lemuel.rbac.adapter.in.web.dto.UpdateRolePermissionsRequest;
import github.lms.lemuel.rbac.application.port.in.RbacUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Admin RBAC", description = "관리자용 역할·권한(RBAC) 관리 API")
@RestController
@RequestMapping("/admin/rbac")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRbacController {

    private final RbacUseCase rbacUseCase;

    /**
     * 역할 목록 조회 (각 역할의 permissionCodes 포함)
     */
    @Operation(summary = "역할 목록 조회", description = "전체 역할 목록을 조회한다. 각 역할에 부여된 권한 ID·코드 포함.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (ADMIN 필요)")
    })
    @GetMapping("/roles")
    public ResponseEntity<List<RoleResponse>> getRoles() {
        List<RoleResponse> responses = rbacUseCase.getAllRoles().stream()
                .map(RoleResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * 전체 권한 목록 조회 (평면 배열)
     */
    @Operation(summary = "전체 권한 목록 조회", description = "시스템에 정의된 모든 권한을 평면 배열로 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping("/permissions")
    public ResponseEntity<List<PermissionResponse>> getPermissions() {
        List<PermissionResponse> responses = rbacUseCase.getAllPermissions().stream()
                .map(PermissionResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * 역할 단건 조회 (권한 포함)
     */
    @Operation(summary = "역할 단건 조회", description = "역할 ID로 역할 상세 정보 및 부여된 권한을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "역할을 찾을 수 없음")
    })
    @GetMapping("/roles/{id}")
    public ResponseEntity<RoleResponse> getRole(
            @Parameter(description = "역할 ID", required = true) @PathVariable Long id) {
        return ResponseEntity.ok(RoleResponse.from(rbacUseCase.getRoleById(id)));
    }

    /**
     * 역할 권한 매트릭스 저장 (전체 교체)
     */
    @Operation(summary = "역할 권한 매트릭스 저장",
            description = "역할에 부여할 권한 ID 목록을 전달하면 기존 권한을 전체 교체한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "404", description = "역할을 찾을 수 없음")
    })
    @PutMapping("/roles/{id}/permissions")
    public ResponseEntity<RoleResponse> updateRolePermissions(
            @Parameter(description = "역할 ID", required = true) @PathVariable Long id,
            @RequestBody UpdateRolePermissionsRequest request) {
        return ResponseEntity.ok(
                RoleResponse.from(rbacUseCase.updateRolePermissions(id, request.permissionIds()))
        );
    }
}
