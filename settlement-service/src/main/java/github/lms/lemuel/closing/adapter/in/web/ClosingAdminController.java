package github.lms.lemuel.closing.adapter.in.web;

import github.lms.lemuel.closing.adapter.in.web.response.MonthlyClosingResponse;
import github.lms.lemuel.closing.adapter.in.web.response.MonthlyClosingRunResponse;
import github.lms.lemuel.closing.application.port.in.GetMonthlyClosingUseCase;
import github.lms.lemuel.closing.application.port.in.RunMonthlyClosingUseCase;
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
 * 정보계 월마감 운영자 콘솔.
 *
 * <p>인가: {@code /admin/monthly-closing/**} 는 shared-common SecurityConfig 의
 * {@code .requestMatchers("/admin/**").hasRole("ADMIN")} 게이트를 상속한다.
 *
 * <p>절차: {@code POST /{ym}/run} 으로 마감 실행(재실행 멱등 — 기간 마트 전체 교체) →
 * {@code GET /{ym}} 으로 run 요약 + 셀러 마트 확인. 원장 마감된 기간의 재실행은 409.
 */
@Tag(name = "Monthly Closing Admin", description = "정보계 월마감 — 셀러 월 정산 마트 (ADMIN)")
@RestController
@RequestMapping("/admin/monthly-closing")
public class ClosingAdminController {

    private final RunMonthlyClosingUseCase runUseCase;
    private final GetMonthlyClosingUseCase getUseCase;

    public ClosingAdminController(RunMonthlyClosingUseCase runUseCase,
                                  GetMonthlyClosingUseCase getUseCase) {
        this.runUseCase = runUseCase;
        this.getUseCase = getUseCase;
    }

    @Operation(summary = "월마감 실행",
            description = "대상 월의 DONE 정산을 셀러별 집계해 마트 적재. 재실행은 기간 단위 교체(멱등). "
                    + "원장 마감된 기간에 COMPLETED 마트가 있으면 409.")
    @PostMapping("/{periodYm}/run")
    public ResponseEntity<MonthlyClosingRunResponse> run(@PathVariable String periodYm) {
        return ResponseEntity.ok(
                MonthlyClosingRunResponse.from(runUseCase.run(parse(periodYm), currentOperator())));
    }

    @Operation(summary = "월마감 조회", description = "최신 run 요약 + 셀러 마트 행. 마감 이력 없으면 404.")
    @GetMapping("/{periodYm}")
    public ResponseEntity<MonthlyClosingResponse> get(@PathVariable String periodYm) {
        return ResponseEntity.ok(MonthlyClosingResponse.from(getUseCase.getClosing(parse(periodYm))));
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
