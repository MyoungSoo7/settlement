package github.lms.lemuel.reservation.application.port.in;

import github.lms.lemuel.reservation.domain.Reservation;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface RegisterReservationUseCase {

    Reservation register(RegisterReservationCommand command);

    /**
     * 시공 예약 등록 커맨드. companyId 는 인증된 업체 회원으로부터 주입된다.
     */
    record RegisterReservationCommand(
            Long companyId,
            LocalDate scheduledDate,
            String siteAddress,
            String sitePassword,
            String siteManagerName,
            String siteManagerPhone,
            Long productId,
            String woodSpecies,
            String brand,
            String productName,
            String productSize,
            BigDecimal constructionArea,
            boolean fieldMeasured,
            boolean expansion,
            BigDecimal expansionArea,
            boolean newFloor,
            boolean baseboard,
            boolean protectionWork,
            BigDecimal protectionArea,
            String note
    ) {
        public RegisterReservationCommand {
            if (companyId == null) {
                throw new IllegalArgumentException("companyId is required");
            }
            if (expansionArea == null) {
                expansionArea = BigDecimal.ZERO;
            }
            if (protectionArea == null) {
                protectionArea = BigDecimal.ZERO;
            }
        }
    }
}
