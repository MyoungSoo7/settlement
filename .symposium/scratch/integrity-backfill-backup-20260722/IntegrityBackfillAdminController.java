package github.lms.lemuel.integrity.adapter.in.web;

import github.lms.lemuel.integrity.application.port.in.RunBackfillUseCase;
import github.lms.lemuel.integrity.domain.BackfillReport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 과거 데이터 멱등 백필 admin 경로 (시드 P0-4) — report-first 운영을 위해 {@code dryRun}
 * 기본값이 {@code true} 다: 실제 정정은 운영자가 dry run 리포트를 확인한 뒤
 * {@code dryRun=false} 를 명시해야 실행된다.
 *
 * <p>인가: {@code /admin/integrity/**} 는 shared-common SecurityConfig 가 ADMIN/MANAGER 로 게이트.
 */
@RestController
@RequestMapping("/admin/integrity/backfill")
public class IntegrityBackfillAdminController {

    private static final int MAX_RANGE_DAYS = 92;
    private static final int MAX_PAGE_SIZE = 1000;

    private final RunBackfillUseCase useCase;

    public IntegrityBackfillAdminController(RunBackfillUseCase useCase) {
        this.useCase = useCase;
    }

    /** INV-6: 확정됐지만 Payout 이 없는 과거 정산에 즉시지급 Payout 을 멱등 생성한다. */
    @PostMapping("/payouts")
    public BackfillReport backfillPayouts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "500") int pageSize,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        validate(from, to, pageSize);
        return useCase.backfillPayouts(from, to, pageSize, dryRun);
    }

    /** INV-5: 역분개 없는 조정(환불·차지백·PG대사)에 균형 역분개를 멱등 적재한다. */
    @PostMapping("/adjustment-reversals")
    public BackfillReport backfillAdjustmentReversals(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "500") int pageSize,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        validate(from, to, pageSize);
        return useCase.backfillAdjustmentReversals(from, to, pageSize, dryRun);
    }

    private static void validate(LocalDate from, LocalDate to, int pageSize) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from <= to 여야 합니다");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "백필 구간은 최대 " + MAX_RANGE_DAYS + "일입니다");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pageSize 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다");
        }
    }
}
