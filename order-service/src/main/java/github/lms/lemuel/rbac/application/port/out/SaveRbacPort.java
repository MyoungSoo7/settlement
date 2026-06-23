package github.lms.lemuel.rbac.application.port.out;

import java.util.List;

public interface SaveRbacPort {

    /**
     * 역할의 권한 매핑을 전체 교체한다.
     * 기존 role_permissions 레코드를 삭제하고 새 permissionIds 로 재삽입.
     */
    void replaceRolePermissions(Long roleId, List<Long> permissionIds);
}
