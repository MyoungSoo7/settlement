package github.lms.lemuel.reservation.application.service;

import github.lms.lemuel.reservation.application.port.in.RegisterReservationUseCase.RegisterReservationCommand;
import github.lms.lemuel.reservation.application.port.out.SaveReservationPort;
import github.lms.lemuel.reservation.domain.Reservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterReservationServiceTest {

    @Test
    @DisplayName("register calculates protection fee and additional fee")
    void register_calculatesPricingFees() {
        AtomicReference<Reservation> saved = new AtomicReference<>();
        SaveReservationPort saveReservationPort = reservation -> {
            saved.set(reservation);
            reservation.setId(1L);
            return reservation;
        };
        RegisterReservationService service = new RegisterReservationService(saveReservationPort);

        Reservation result = service.register(new RegisterReservationCommand(
                10L,
                LocalDate.of(2026, 7, 1),
                "Seoul Gangnam 1",
                "1234",
                "site manager",
                "010-1234-5678",
                20L,
                "oak",
                "brand",
                "premium floor",
                "190x1200",
                new BigDecimal("30"),
                true,
                true,
                new BigDecimal("5"),
                true,
                true,
                true,
                new BigDecimal("12"),
                "note"
        ));

        assertThat(saved.get()).isSameAs(result);
        assertThat(result.getProtectionFee()).isEqualByComparingTo("60000");
        assertThat(result.getAdditionalFee()).isEqualByComparingTo("215000");
    }
}
