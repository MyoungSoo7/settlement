package github.lms.lemuel.reservation.application.service;

import github.lms.lemuel.reservation.domain.Reservation;

import java.math.BigDecimal;

class ReservationPricingPolicy {

    private static final BigDecimal PROTECTION_UNIT_PRICE = new BigDecimal("5000");
    private static final BigDecimal EXPANSION_UNIT_PRICE = new BigDecimal("15000");
    private static final BigDecimal BASEBOARD_UNIT_PRICE = new BigDecimal("3000");
    private static final BigDecimal NEW_FLOOR_FLAT_FEE = new BigDecimal("50000");

    PricingResult calculate(Reservation reservation) {
        BigDecimal protectionFee = reservation.isProtectionWork()
                ? reservation.getProtectionArea().multiply(PROTECTION_UNIT_PRICE)
                : BigDecimal.ZERO;

        BigDecimal additionalFee = BigDecimal.ZERO;
        if (reservation.isExpansion()) {
            additionalFee = additionalFee.add(reservation.getExpansionArea().multiply(EXPANSION_UNIT_PRICE));
        }
        if (reservation.isBaseboard()) {
            additionalFee = additionalFee.add(reservation.getConstructionArea().multiply(BASEBOARD_UNIT_PRICE));
        }
        if (reservation.isNewFloor()) {
            additionalFee = additionalFee.add(NEW_FLOOR_FLAT_FEE);
        }

        return new PricingResult(protectionFee, additionalFee);
    }

    record PricingResult(BigDecimal protectionFee, BigDecimal additionalFee) {
    }
}
