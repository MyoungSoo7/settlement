package github.lms.lemuel.reservation.application.service;

import github.lms.lemuel.reservation.application.port.out.LoadReservationPort;
import github.lms.lemuel.reservation.application.port.out.SaveReservationPort;
import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.domain.ReservationStatus;
import github.lms.lemuel.reservation.domain.exception.ReservationNotFoundException;
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
