package github.lms.lemuel.reservation.adapter.in.web.response;

import github.lms.lemuel.reservation.domain.Reservation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시공 예약 응답 DTO.
 */
public record ReservationResponse(
        Long id,
        Long companyId,
        String status,
        LocalDate scheduledDate,
        String siteAddress,
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
        BigDecimal protectionFee,
        BigDecimal additionalFee,
        String note,
        LocalDateTime createdAt
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.getId(),
                r.getCompanyId(),
                r.getStatus().name(),
                r.getScheduledDate(),
                r.getSiteAddress(),
                r.getSiteManagerName(),
                r.getSiteManagerPhone(),
                r.getProductId(),
                r.getWoodSpecies(),
                r.getBrand(),
                r.getProductName(),
                r.getProductSize(),
                r.getConstructionArea(),
                r.isFieldMeasured(),
                r.isExpansion(),
                r.getExpansionArea(),
                r.isNewFloor(),
                r.isBaseboard(),
                r.isProtectionWork(),
                r.getProtectionArea(),
                r.getProtectionFee(),
                r.getAdditionalFee(),
                r.getNote(),
                r.getCreatedAt()
        );
    }
}
