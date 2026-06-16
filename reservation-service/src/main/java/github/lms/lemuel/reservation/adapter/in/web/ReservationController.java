package github.lms.lemuel.reservation.adapter.in.web;

import github.lms.lemuel.reservation.adapter.in.web.request.CancelReservationRequest;
import github.lms.lemuel.reservation.adapter.in.web.request.RegisterReservationRequest;
import github.lms.lemuel.reservation.adapter.in.web.response.ReservationResponse;
import github.lms.lemuel.reservation.application.port.in.ChangeReservationStatusUseCase;
import github.lms.lemuel.reservation.application.port.in.GetReservationUseCase;
import github.lms.lemuel.reservation.application.port.in.RegisterReservationUseCase;
import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.reservation.domain.ReservationStatus;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.reservation.domain.exception.ForbiddenReservationAccessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 시공 예약 API Controller
 */
@Tag(name = "Reservation", description = "시공 예약 등록/조회 API")
@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final RegisterReservationUseCase registerReservationUseCase;
    private final GetReservationUseCase getReservationUseCase;
    private final ChangeReservationStatusUseCase changeReservationStatusUseCase;

    @Operation(summary = "시공 예약 등록", description = "인증된 업체 회원이 마루 시공 예약을 등록한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "업체 회원만 등록 가능")
    })
    @PostMapping
    public ResponseEntity<ReservationResponse> register(
            @Valid @RequestBody RegisterReservationRequest request) {
        AuthPrincipal company = requireCompany();

        Reservation reservation = registerReservationUseCase.register(
                new RegisterReservationUseCase.RegisterReservationCommand(
                        company.userId(),
                        request.getScheduledDate(),
                        request.getSiteAddress(),
                        request.getSitePassword(),
                        request.getSiteManagerName(),
                        request.getSiteManagerPhone(),
                        request.getProductId(),
                        request.getWoodSpecies(),
                        request.getBrand(),
                        request.getProductName(),
                        request.getProductSize(),
                        request.getConstructionArea(),
                        request.isFieldMeasured(),
                        request.isExpansion(),
                        request.getExpansionArea(),
                        request.isNewFloor(),
                        request.isBaseboard(),
                        request.isProtectionWork(),
                        request.getProtectionArea(),
                        request.getNote()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(reservation));
    }

    @Operation(summary = "예약 단건 조회", description = "예약 ID로 단건 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "예약을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ReservationResponse> getById(
            @Parameter(description = "예약 ID", required = true) @PathVariable Long id) {
        return ResponseEntity.ok(ReservationResponse.from(getReservationUseCase.getById(id)));
    }

    @Operation(summary = "내 예약 현황 조회", description = "로그인한 업체 회원의 예약 목록을 조회한다.")
    @GetMapping("/my")
    public ResponseEntity<List<ReservationResponse>> getMine() {
        AuthPrincipal company = requireCompany();
        List<ReservationResponse> result = getReservationUseCase.getByCompany(company.userId())
                .stream()
                .map(ReservationResponse::from)
                .toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "내 배정 작업 조회", description = "로그인한 시공기사에게 배정된 예약을 일정 순으로 조회한다.")
    @GetMapping("/assigned/my")
    public ResponseEntity<List<ReservationResponse>> getMyAssignments() {
        AuthPrincipal technician = requireTechnician();
        List<ReservationResponse> result = getReservationUseCase.getByTechnician(technician.userId())
                .stream()
                .map(ReservationResponse::from)
                .toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "관리자 대시보드 조회",
            description = "시공일자(date)/상태(status)로 예약을 조회한다. 둘 다 선택이며, 없으면 전체. 일정 오름차순.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 상태값"),
            @ApiResponse(responseCode = "403", description = "관리자만 조회 가능")
    })
    @GetMapping("/admin")
    public ResponseEntity<List<ReservationResponse>> dashboard(
            @Parameter(description = "시공일자 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "예약 상태 (REQUESTED, CONFIRMED, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELED)")
            @RequestParam(required = false) String status) {
        requireAdmin();
        ReservationStatus statusFilter = parseStatus(status);
        List<ReservationResponse> result = getReservationUseCase.search(date, statusFilter)
                .stream()
                .map(ReservationResponse::from)
                .toList();
        return ResponseEntity.ok(result);
    }

    private ReservationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReservationStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid reservation status: " + status);
        }
    }

    // ── 상태 전이 API ────────────────────────────────────────

    @Operation(summary = "예약 확인", description = "관리자가 접수된 예약을 확인한다. (REQUESTED → CONFIRMED)")
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirm(@PathVariable Long id) {
        requireAdmin();
        return ResponseEntity.ok(ReservationResponse.from(changeReservationStatusUseCase.confirm(id)));
    }

    @Operation(summary = "기사 배정", description = "관리자가 확인된 예약에 시공기사를 배정한다. (CONFIRMED → ASSIGNED)")
    @PostMapping("/{id}/assign")
    public ResponseEntity<ReservationResponse> assign(
            @PathVariable Long id,
            @Valid @RequestBody AssignTechnicianRequest request) {
        requireAdmin();
        return ResponseEntity.ok(ReservationResponse.from(
                changeReservationStatusUseCase.assign(id, request.technicianId())));
    }

    @Operation(summary = "기사 재배정", description = "배정된(ASSIGNED) 예약의 담당 기사를 다른 기사로 교체한다. 시공 시작 전에만 가능.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재배정 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 기사 또는 동일 기사"),
            @ApiResponse(responseCode = "403", description = "관리자만 가능"),
            @ApiResponse(responseCode = "404", description = "예약/기사를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "ASSIGNED 상태가 아니면 재배정 불가")
    })
    @PostMapping("/{id}/reassign")
    public ResponseEntity<ReservationResponse> reassign(
            @PathVariable Long id,
            @Valid @RequestBody AssignTechnicianRequest request) {
        requireAdmin();
        return ResponseEntity.ok(ReservationResponse.from(
                changeReservationStatusUseCase.reassign(id, request.technicianId())));
    }

    @Operation(summary = "시공 시작", description = "배정된 예약의 시공을 시작한다. (ASSIGNED → IN_PROGRESS)")
    @PostMapping("/{id}/start")
    public ResponseEntity<ReservationResponse> start(@PathVariable Long id) {
        requireAdmin();
        return ResponseEntity.ok(ReservationResponse.from(changeReservationStatusUseCase.start(id)));
    }

    @Operation(summary = "시공 완료", description = "진행 중인 예약을 완료 처리한다. (IN_PROGRESS → COMPLETED)")
    @PostMapping("/{id}/complete")
    public ResponseEntity<ReservationResponse> complete(@PathVariable Long id) {
        requireAdmin();
        return ResponseEntity.ok(ReservationResponse.from(changeReservationStatusUseCase.complete(id)));
    }

    @Operation(summary = "예약 취소", description = "관리자 또는 예약을 등록한 업체 회원이 비종료 예약을 취소한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 성공"),
            @ApiResponse(responseCode = "403", description = "본인 예약 또는 관리자만 취소 가능"),
            @ApiResponse(responseCode = "404", description = "예약을 찾을 수 없음")
    })
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancel(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) CancelReservationRequest request) {
        AuthPrincipal actor = currentUser();
        if (!"ADMIN".equals(actor.role())) {
            // 업체 회원은 본인이 등록한 예약만 취소 가능
            Reservation existing = getReservationUseCase.getById(id);
            if (!"COMPANY".equals(actor.role()) || !existing.getCompanyId().equals(actor.userId())) {
                throw new ForbiddenReservationAccessException("Only the owning COMPANY member or an ADMIN can cancel");
            }
        }
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(ReservationResponse.from(changeReservationStatusUseCase.cancel(id, reason)));
    }

    private AuthPrincipal currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal p)
                || p.userId() == null) {
            throw new ForbiddenReservationAccessException("Authentication required");
        }
        return p;
    }

    private AuthPrincipal requireAdmin() {
        AuthPrincipal p = currentUser();
        if (!"ADMIN".equals(p.role()) && !"MANAGER".equals(p.role())) {
            throw new ForbiddenReservationAccessException("Only ADMIN or MANAGER can change reservation status");
        }
        return p;
    }

    private AuthPrincipal requireTechnician() {
        AuthPrincipal p = currentUser();
        if (!"TECHNICIAN".equals(p.role()) && !"ADMIN".equals(p.role())) {
            throw new ForbiddenReservationAccessException("Only TECHNICIAN can view assigned jobs");
        }
        return p;
    }

    private AuthPrincipal requireCompany() {
        AuthPrincipal p = currentUser();
        if (!"COMPANY".equals(p.role()) && !"ADMIN".equals(p.role())) {
            throw new ForbiddenReservationAccessException("Only COMPANY members can register reservations");
        }
        return p;
    }

    public record AssignTechnicianRequest(
            @jakarta.validation.constraints.NotNull Long technicianId
    ) {}

    public record CancelReservationRequest(
            @jakarta.validation.constraints.Size(max = 500) String reason
    ) {}
}
