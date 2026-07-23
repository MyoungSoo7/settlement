package github.lms.lemuel.ledger.adapter.in.web;

import github.lms.lemuel.ledger.adapter.in.web.response.LedgerPeriodResponse;
import github.lms.lemuel.ledger.adapter.in.web.response.LedgerTrialBalanceResponse;
import github.lms.lemuel.ledger.application.port.in.CloseLedgerPeriodUseCase;
import github.lms.lemuel.ledger.application.port.in.GetLedgerPeriodUseCase;
import github.lms.lemuel.ledger.application.port.in.GetLedgerTrialBalanceUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * 원장 월마감 운영자 콘솔.
 *
 * <p>인가: {@code /admin/ledger-periods/**} 는 shared-common SecurityConfig 의
 * {@code .requestMatchers("/admin/**").hasRole("ADMIN")} 게이트를 상속한다.
 *
 * <p>절차: {@code GET /{ym}/trial-balance} 로 확정 시산표(균형) 확인 → {@code POST /{ym}/close} 로 마감.
 * 마감은 멱등(이미 CLOSED 면 기존 스냅샷 반환). 감사 추적은 {@code ledger_periods.closed_by/closed_at} 컬럼이
 * 곧 이력이다(누가·언제 마감했는지 기간 행 자체에 기록).
 */
@Tag(name = "Ledger Period Admin", description = "원장 월마감·기간잠금·확정 시산표 (ADMIN)")
@RestController
@RequestMapping("/admin/ledger-periods")
public class LedgerPeriodAdminController {

    private final CloseLedgerPeriodUseCase closeUseCase;
    private final GetLedgerPeriodUseCase getPeriodUseCase;
    private final GetLedgerTrialBalanceUseCase trialBalanceUseCase;

    public LedgerPeriodAdminController(CloseLedgerPeriodUseCase closeUseCase,
                                       GetLedgerPeriodUseCase getPeriodUseCase,
                                       GetLedgerTrialBalanceUseCase trialBalanceUseCase) {
        this.closeUseCase = closeUseCase;
        this.getPeriodUseCase = getPeriodUseCase;
        this.trialBalanceUseCase = trialBalanceUseCase;
    }

    @Operation(summary = "기간 상태 조회", description = "행이 없으면 암묵적 OPEN 으로 응답")
    @GetMapping("/{periodYm}")
    public ResponseEntity<LedgerPeriodResponse> status(@PathVariable String periodYm) {
        return ResponseEntity.ok(LedgerPeriodResponse.from(getPeriodUseCase.getStatus(parse(periodYm))));
    }

    @Operation(summary = "기간 확정 시산표 조회", description = "마감 전 균형 확인용 — 계정별 차/대 합계 + balanced")
    @GetMapping("/{periodYm}/trial-balance")
    public ResponseEntity<LedgerTrialBalanceResponse> trialBalance(@PathVariable String periodYm) {
        return ResponseEntity.ok(
                LedgerTrialBalanceResponse.from(trialBalanceUseCase.getForPeriod(parse(periodYm))));
    }

    @Operation(summary = "기간 마감",
            description = "확정 시산표 산출 → 차대 균형 확인 → OPEN→CLOSED 전이 + 합계 스냅샷. "
                    + "멱등(이미 CLOSED 면 기존 스냅샷 반환). 불균형이면 422.")
    @PostMapping("/{periodYm}/close")
    public ResponseEntity<LedgerPeriodResponse> close(@PathVariable String periodYm) {
        return ResponseEntity.ok(
                LedgerPeriodResponse.from(closeUseCase.close(parse(periodYm), currentOperator())));
    }

    private static YearMonth parse(String periodYm) {
        try {
            return YearMonth.parse(periodYm);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("periodYm 형식은 YYYY-MM 이어야 합니다: " + periodYm);
        }
    }

    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "anonymous" : auth.getName();
    }
}
