package github.lms.lemuel.rbac.application.service;

import github.lms.lemuel.rbac.application.port.out.LoadRbacPort;
import github.lms.lemuel.rbac.application.port.out.SaveRbacPort;
import github.lms.lemuel.rbac.domain.Permission;
import github.lms.lemuel.rbac.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RbacService — 역할/권한 조회 및 매핑 교체")
class RbacServiceTest {

    @Mock LoadRbacPort loadRbacPort;
    @Mock SaveRbacPort saveRbacPort;
    @InjectMocks RbacService service;

    @Test
    @DisplayName("getAllRoles — 위임")
    void getAllRoles() {
        when(loadRbacPort.findAllRoles()).thenReturn(List.of(mock(Role.class)));
        assertThat(service.getAllRoles()).hasSize(1);
    }

    @Test
    @DisplayName("getAllPermissions — 위임")
    void getAllPermissions() {
        when(loadRbacPort.findAllPermissions()).thenReturn(List.of(mock(Permission.class)));
        assertThat(service.getAllPermissions()).hasSize(1);
    }

    @Test
    @DisplayName("getRoleById — 존재하면 반환")
    void getRoleById_ok() {
        Role role = mock(Role.class);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(role));
        assertThat(service.getRoleById(1L)).isSameAs(role);
    }

    @Test
    @DisplayName("getRoleById — 없으면 예외")
    void getRoleById_missing() {
        when(loadRbacPort.findRoleById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getRoleById(9L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updateRolePermissions — 교체 후 재조회 반환")
    void updateRolePermissions_ok() {
        Role role = mock(Role.class);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(role));
        Role updated = service.updateRolePermissions(1L, List.of(10L, 20L));
        assertThat(updated).isSameAs(role);
        verify(saveRbacPort).replaceRolePermissions(1L, List.of(10L, 20L));
    }

    @Test
    @DisplayName("updateRolePermissions — permissionIds null 이면 count 0 로그 경로")
    void updateRolePermissions_nullIds() {
        Role role = mock(Role.class);
        when(loadRbacPort.findRoleById(1L)).thenReturn(Optional.of(role));
        Role updated = service.updateRolePermissions(1L, null);
        assertThat(updated).isSameAs(role);
        verify(saveRbacPort).replaceRolePermissions(1L, null);
    }

    @Test
    @DisplayName("updateRolePermissions — 역할 없으면 예외")
    void updateRolePermissions_missing() {
        when(loadRbacPort.findRoleById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateRolePermissions(9L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
