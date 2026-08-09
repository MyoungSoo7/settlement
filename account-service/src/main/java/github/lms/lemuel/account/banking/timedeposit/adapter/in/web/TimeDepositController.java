package github.lms.lemuel.account.banking.timedeposit.adapter.in.web;

import github.lms.lemuel.account.banking.timedeposit.adapter.in.web.dto.OpenTimeDepositRequest;
import github.lms.lemuel.account.banking.timedeposit.adapter.in.web.dto.TimeDepositResponse;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.CloseTimeDepositUseCase;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.OpenTimeDepositUseCase;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.OpenTimeDepositUseCase.OpenTimeDepositCommand;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.TimeDepositQueryUseCase;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.TimeDepositAccessDeniedException;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 정기예금 셀프서비스 API — 본인 계좌 개설·해지·조회. (JWT 인증)
 *
 * <p><b>IDOR 방지</b>: 예금주 식별자는 요청(경로·쿼리·본문) 어디에서도 받지 않고 JWT 주체
 * ({@link AuthPrincipal#userId()}) 에서만 파생한다. 그래서 {@code {depositId}} 만으로 남의 계좌를
 * 지목해도, 응용 서비스가 적재된 {@code depositorId} 와 대조해 403 으로 끊는다.
 * {@code userId} 가 없는 구(舊) 토큰 역시 예금주를 특정할 수 없으므로 403 이다 — 전역 핸들러의
 * 500 폴백을 타지 않도록 {@code ErrorCode.ACCESS_DENIED} 로 명시 매핑한다.
 *
 * <p>개설일·해지일은 클라이언트가 정하지 않는다(서버 시계) — 소급 날짜로 이자를 부풀리는 경로 차단.
 */
@RestController
@RequestMapping("/api/banking/time-deposits")
public class TimeDepositController {

    private final OpenTimeDepositUseCase openTimeDepositUseCase;
    private final CloseTimeDepositUseCase closeTimeDepositUseCase;
    private final TimeDepositQueryUseCase timeDepositQueryUseCase;

    public TimeDepositController(OpenTimeDepositUseCase openTimeDepositUseCase,
                                 CloseTimeDepositUseCase closeTimeDepositUseCase,
                                 TimeDepositQueryUseCase timeDepositQueryUseCase) {
        this.openTimeDepositUseCase = openTimeDepositUseCase;
        this.closeTimeDepositUseCase = closeTimeDepositUseCase;
        this.timeDepositQueryUseCase = timeDepositQueryUseCase;
    }

    @PostMapping
    public ResponseEntity<TimeDepositResponse> open(@RequestBody OpenTimeDepositRequest request) {
        return ResponseEntity.ok(TimeDepositResponse.from(
                openTimeDepositUseCase.open(new OpenTimeDepositCommand(
                        currentDepositorId(),
                        request.productName(),
                        request.principal(),
                        request.annualRate(),
                        request.earlyTerminationRate(),
                        request.compounding(),
                        request.termMonthsOrZero()))));
    }

    /** 만기 해지 — 약정이율 적용. */
    @PostMapping("/{depositId}/close")
    public ResponseEntity<TimeDepositResponse> closeOnMaturity(@PathVariable Long depositId) {
        return ResponseEntity.ok(TimeDepositResponse.from(
                closeTimeDepositUseCase.closeOnMaturity(currentDepositorId(), depositId)));
    }

    /** 중도 해지 — 중도해지이율 적용. */
    @PostMapping("/{depositId}/close-early")
    public ResponseEntity<TimeDepositResponse> closeEarly(@PathVariable Long depositId) {
        return ResponseEntity.ok(TimeDepositResponse.from(
                closeTimeDepositUseCase.closeEarly(currentDepositorId(), depositId)));
    }

    @GetMapping("/{depositId}")
    public ResponseEntity<TimeDepositResponse> get(@PathVariable Long depositId) {
        return ResponseEntity.ok(TimeDepositResponse.from(
                timeDepositQueryUseCase.get(currentDepositorId(), depositId)));
    }

    @GetMapping
    public ResponseEntity<List<TimeDepositResponse>> listMine() {
        return ResponseEntity.ok(timeDepositQueryUseCase.listMine(currentDepositorId()).stream()
                .map(TimeDepositResponse::from)
                .toList());
    }

    /**
     * 예금주 식별자의 <b>유일한</b> 출처 — SecurityContext 의 JWT 주체.
     * {@code OwnerType.DEPOSITOR} 규약대로 userId 숫자 문자열로 변환한다.
     */
    private static String currentDepositorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal && principal.userId() != null) {
            return String.valueOf(principal.userId());
        }
        throw TimeDepositAccessDeniedException.unidentifiedDepositor();
    }
}
