package github.lms.lemuel.reservation.adapter.in.web;

import github.lms.lemuel.reservation.adapter.in.web.request.CancelReservationRequest;
import github.lms.lemuel.reservation.adapter.in.web.request.RegisterReservationRequest;
import github.lms.lemuel.reservation.adapter.in.web.response.ReservationResponse;
import github.lms.lemuel.reservation.application.port.in.ChangeReservationStatusUseCase;
import github.lms.lemuel.reservation.application.port.in.GetReservationUseCase;
import github.lms.lemuel.reservation.application.port.in.RegisterReservationUseCase;
import github.lms.lemuel.reservation.domain.Reservation;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

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
    private final LoadUserPort loadUserPort;

    @Operation(summary = "시공 예약 등록", description = "인증된 업체 회원이 마루 시공 예약을 등록한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "업체 회원만 등록 가능")
    })
    @PostMapping
    public ResponseEntity<ReservationResponse> register(
            @Valid @RequestBody RegisterReservationRequest request) {
        User company = requireCompany();

        Reservation reservation = registerReservationUseCase.register(
                new RegisterReservationUseCase.RegisterReservationCommand(
                        company.getId(),
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
        User company = requireCompany();
        List<ReservationResponse> result = getReservationUseCase.getByCompany(company.getId())
                .stream()
                .map(ReservationResponse::from)
                .toList();
        return ResponseEntity.ok(result);
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
    public ResponseEntity<ReservationResponse> assign(@PathVariable Long id) {
        requireAdmin();
        return ResponseEntity.ok(ReservationResponse.from(changeReservationStatusUseCase.assign(id)));
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
        User actor = requireAuthenticated();
        if (actor.getRole() != UserRole.ADMIN) {
            // 업체 회원은 본인이 등록한 예약만 취소 가능
            Reservation existing = getReservationUseCase.getById(id);
            if (actor.getRole() != UserRole.COMPANY || !existing.getCompanyId().equals(actor.getId())) {
                throw new InvalidCredentialsException("Only the owning COMPANY member or an ADMIN can cancel");
            }
        }
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(ReservationResponse.from(changeReservationStatusUseCase.cancel(id, reason)));
    }

    private User requireAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new InvalidCredentialsException("Authentication required");
        }
        return loadUserPort.findByEmail(authentication.getName())
                .orElseThrow(() -> new UserNotFoundException(authentication.getName()));
    }

    private User requireAdmin() {
        User user = requireAuthenticated();
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.MANAGER) {
            throw new InvalidCredentialsException("Only ADMIN or MANAGER can change reservation status");
        }
        return user;
    }

    private User requireCompany() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new InvalidCredentialsException("Authentication required");
        }
        User user = loadUserPort.findByEmail(authentication.getName())
                .orElseThrow(() -> new UserNotFoundException(authentication.getName()));
        if (user.getRole() != UserRole.COMPANY && user.getRole() != UserRole.ADMIN) {
            throw new InvalidCredentialsException("Only COMPANY members can register reservations");
        }
        return user;
    }
}
