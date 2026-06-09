package github.lms.lemuel.reservation.adapter.in.web;

import github.lms.lemuel.reservation.adapter.in.web.request.RegisterReservationRequest;
import github.lms.lemuel.reservation.adapter.in.web.response.ReservationResponse;
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
