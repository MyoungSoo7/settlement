package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TechnicianProjectionBackfillServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock PublishUserEventPort publishUserEventPort;
    @InjectMocks TechnicianProjectionBackfillService service;

    private User user(Long id, UserRole role) {
        User u = User.createWithProfile(id + "@x.com", "hash", role, "name", "010-0000-0000");
        u.setId(id);
        return u;
    }

    @Test
    @DisplayName("백필: TECHNICIAN 만 재발행하고 그 건수를 반환한다")
    void backfill_publishesOnlyTechnicians() {
        when(loadUserPort.findAll()).thenReturn(List.of(
                user(1L, UserRole.TECHNICIAN),
                user(2L, UserRole.COMPANY),
                user(3L, UserRole.TECHNICIAN),
                user(4L, UserRole.USER)));

        int published = service.backfillTechnicians();

        assertThat(published).isEqualTo(2);
        verify(publishUserEventPort).publishMembershipChanged(eq(1L), eq("TECHNICIAN"), eq("APPROVED"), anyBoolean());
        verify(publishUserEventPort).publishMembershipChanged(eq(3L), eq("TECHNICIAN"), eq("APPROVED"), anyBoolean());
        verify(publishUserEventPort, never()).publishMembershipChanged(eq(2L), eq("COMPANY"), eq("APPROVED"), anyBoolean());
        verify(publishUserEventPort, never()).publishMembershipChanged(eq(4L), eq("USER"), eq("APPROVED"), anyBoolean());
    }

    @Test
    @DisplayName("백필: 기사가 없으면 0 을 반환하고 발행하지 않는다")
    void backfill_noTechnicians() {
        when(loadUserPort.findAll()).thenReturn(List.of(user(1L, UserRole.USER)));

        int published = service.backfillTechnicians();

        assertThat(published).isZero();
        verify(publishUserEventPort, never())
                .publishMembershipChanged(eq(1L), eq("USER"), eq("APPROVED"), anyBoolean());
    }
}
