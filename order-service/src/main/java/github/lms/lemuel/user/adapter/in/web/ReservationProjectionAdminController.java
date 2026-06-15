package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.user.application.port.in.BackfillTechnicianProjectionUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * reservation-service 기사 프로젝션 운영 엔드포인트 (ADMIN 전용 — SecurityConfig 에서 강제).
 */
@Tag(name = "Admin - Reservation Projection", description = "기사 프로젝션 백필")
@RestController
@RequestMapping("/admin/reservation-projection")
@RequiredArgsConstructor
public class ReservationProjectionAdminController {

    private final BackfillTechnicianProjectionUseCase backfillUseCase;

    @Operation(summary = "기사 프로젝션 백필",
            description = "기존 TECHNICIAN 회원의 멤버십 이벤트를 재발행해 reservation-service 의 technician_view 를 시드한다. 멱등.")
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill() {
        int published = backfillUseCase.backfillTechnicians();
        return ResponseEntity.ok(Map.of("published", published));
    }
}
