package github.lms.lemuel.reservation.application.service;

import github.lms.lemuel.reservation.application.port.out.LoadReservationPort;
import github.lms.lemuel.reservation.application.port.out.SaveReservationPort;
import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.domain.ReservationStatus;
import github.lms.lemuel.reservation.domain.exception.ReservationNotFoundException;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangeReservationStatusServiceTest {

    @Mock LoadReservationPort loadReservationPort;
    @Mock SaveReservationPort saveReservationPort;
    @Mock LoadUserPort loadUserPort;
    @InjectMocks ChangeReservationStatusService service;

    private Reservation requested() {
        return Reservation.register(
                10L,
                LocalDate.of(2026, 7, 1),
                "서울시 강남구 1번지",
                "홍길동",
                "010-1234-5678",
                new BigDecimal("32.50"));
    }

    private Reservation confirmed() {
        Reservation r = requested();
        r.confirm();
        return r;
    }

    private User technician(Long id) {
        User u = User.createWithProfile("tech@x.com", "hash", UserRole.TECHNICIAN, "기사", "010-9999-8888");
        u.setId(id);
        return u; // 기본 APPROVED
    }

    @Test
    @DisplayName("confirm: REQUESTED → CONFIRMED 로 저장한다")
    void confirm_success() {
        Reservation r = requested();
        when(loadReservationPort.findById(1L)).thenReturn(Optional.of(r));
        when(saveReservationPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Reservation result = service.confirm(1L);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(saveReservationPort).save(r);
    }

    @Test
    @DisplayName("cancel: 사유와 함께 CANCELED 로 저장한다")
    void cancel_success() {
        Reservation r = requested();
        when(loadReservationPort.findById(1L)).thenReturn(Optional.of(r));
        when(saveReservationPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Reservation result = service.cancel(1L, "고객 변심");

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(result.getCanceledReason()).isEqualTo("고객 변심");
    }

    @Test
    @DisplayName("assign: APPROVED TECHNICIAN 배정 시 ASSIGNED + technicianId 저장")
    void assign_success() {
        Reservation r = confirmed();
        when(loadReservationPort.findById(1L)).thenReturn(Optional.of(r));
        when(loadUserPort.findById(20L)).thenReturn(Optional.of(technician(20L)));
        when(saveReservationPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Reservation result = service.assign(1L, 20L);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.ASSIGNED);
        assertThat(result.getTechnicianId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("assign: 대상이 TECHNICIAN 이 아니면 예외, 저장하지 않는다")
    void assign_notTechnician() {
        User notTech = User.createWithProfile("c@x.com", "hash", UserRole.COMPANY, "업체", "010-1111-2222");
        notTech.setId(20L);
        when(loadUserPort.findById(20L)).thenReturn(Optional.of(notTech));

        assertThatThrownBy(() -> service.assign(1L, 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TECHNICIAN");

        verify(saveReservationPort, never()).save(any());
    }

    @Test
    @DisplayName("assign: 정지(SUSPENDED) 기사는 배정 불가")
    void assign_suspendedTechnician() {
        User tech = technician(20L);
        tech.suspendMembership(); // APPROVED → SUSPENDED
        when(loadUserPort.findById(20L)).thenReturn(Optional.of(tech));

        assertThatThrownBy(() -> service.assign(1L, 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVED");

        verify(saveReservationPort, never()).save(any());
    }

    @Test
    @DisplayName("assign: 기사가 존재하지 않으면 UserNotFoundException")
    void assign_technicianNotFound() {
        when(loadUserPort.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(1L, 404L))
                .isInstanceOf(UserNotFoundException.class);

        verify(saveReservationPort, never()).save(any());
    }

    @Test
    @DisplayName("잘못된 순서의 전이는 IllegalStateException, 저장하지 않는다")
    void invalidTransition_doesNotSave() {
        Reservation r = requested(); // REQUESTED 상태에서 바로 start 불가
        when(loadReservationPort.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(IllegalStateException.class);

        verify(saveReservationPort, never()).save(any());
    }

    @Test
    @DisplayName("예약이 없으면 ReservationNotFoundException")
    void notFound() {
        when(loadReservationPort.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(999L))
                .isInstanceOf(ReservationNotFoundException.class);

        verify(saveReservationPort, never()).save(any());
    }
}
