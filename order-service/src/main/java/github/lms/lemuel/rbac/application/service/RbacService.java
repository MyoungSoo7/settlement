package github.lms.lemuel.rbac.application.service;

import github.lms.lemuel.rbac.application.port.in.RbacUseCase;
import github.lms.lemuel.rbac.application.port.out.LoadRbacPort;
import github.lms.lemuel.rbac.application.port.out.SaveRbacPort;
import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RbacService implements RbacUseCase {

    private final LoadRbacPort loadRbacPort;
    private final SaveRbacPort saveRbacPort;

    @Override
    @Transactional(readOnly = true)
    public List<Role> getAllRoles() {
        return loadRbacPort.findAllRoles();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Permission> getAllPermissions() {
        return loadRbacPort.findAllPermissions();
    }

    @Override
    @Transactional(readOnly = true)
    public Role getRoleById(Long id) {
        return loadRbacPort.findRoleById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 역할 ID: " + id));
    }

    @Override
    public Role updateRolePermissions(Long roleId, List<Long> permissionIds) {
        // 역할 존재 여부 확인
        loadRbacPort.findRoleById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 역할 ID: " + roleId));

        saveRbacPort.replaceRolePermissions(roleId, permissionIds);
        log.info("역할 권한 갱신 완료: roleId={}, permissionCount={}", roleId,
                permissionIds == null ? 0 : permissionIds.size());

        return loadRbacPort.findRoleById(roleId)
                .orElseThrow(() -> new IllegalStateException("역할 재조회 실패: " + roleId));
    }
}
