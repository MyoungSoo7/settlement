package github.lms.lemuel.reservation.adapter.in.web.request;

import jakarta.validation.constraints.Size;

/**
 * 시공 예약 취소 요청 DTO.
 */
public record CancelReservationRequest(
        @Size(max = 500, message = "취소 사유는 500자 이내여야 합니다")
        String reason
) {
}
