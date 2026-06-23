package github.lms.lemuel.rbac.application.port.in;

import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;

import java.util.List;

public interface RbacUseCase {

    /** 역할 목록 조회 (각 역할의 권한 포함) */
    List<Role> getAllRoles();

    /** 전체 권한 목록 조회 (평면 배열) */
    List<Permission> getAllPermissions();

    /** 역할 단건 조회 (권한 포함) */
    Role getRoleById(Long id);

    /**
     * 역할의 권한 매트릭스 전체 교체.
     * permissionIds 에 포함된 권한만 남기고 나머지는 제거.
     *
     * @return 갱신된 역할(권한 포함)
     */
    Role updateRolePermissions(Long roleId, List<Long> permissionIds);
}
