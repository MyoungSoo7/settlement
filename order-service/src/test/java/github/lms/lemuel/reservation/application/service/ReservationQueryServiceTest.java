package github.lms.lemuel.reservation.application.service;

import github.lms.lemuel.reservation.application.port.out.LoadReservationPort;
import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.domain.exception.ReservationNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationQueryServiceTest {

    @Mock LoadReservationPort loadReservationPort;
    @InjectMocks ReservationQueryService service;

    private Reservation sample() {
        return Reservation.register(
                10L, LocalDate.of(2026, 7, 1), "주소", "담당자", "010-0000-0000", new BigDecimal("10"));
    }

    @Test
    @DisplayName("getById: 존재하면 반환")
    void getById_found() {
        Reservation r = sample();
        when(loadReservationPort.findById(1L)).thenReturn(Optional.of(r));

        assertThat(service.getById(1L)).isSameAs(r);
    }

    @Test
    @DisplayName("getById: 없으면 ReservationNotFoundException")
    void getById_notFound() {
        when(loadReservationPort.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(999L))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    @DisplayName("getByCompany: 업체 예약 목록 위임")
    void getByCompany() {
        when(loadReservationPort.findByCompanyId(10L)).thenReturn(List.of(sample()));

        assertThat(service.getByCompany(10L)).hasSize(1);
    }

    @Test
    @DisplayName("getByTechnician: 기사 배정 작업 목록 위임")
    void getByTechnician() {
        when(loadReservationPort.findByTechnicianId(20L)).thenReturn(List.of(sample(), sample()));

        List<Reservation> result = service.getByTechnician(20L);

        assertThat(result).hasSize(2);
    }
}
