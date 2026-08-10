package github.lms.lemuel.settlement.adapter.in.web.admin;

import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase.HoldbackReleasePreview;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 홀드백 해제 미리보기 콘솔.
 *
 * <pre>
 *   GET /admin/settlements/holdback-preview?date=2026-09-01&amp;limit=200
 * </pre>
 *
 * <p>해제는 지급 요청과 회계 이벤트를 함께 만들어내므로, 규모를 먼저 보고 확정할 수 있어야 한다.
 * 조회 전용이라 해제를 트리거하지 않는다 — 실제 해제는 배치(HoldbackReleaseScheduler)나
 * 재실행 콘솔(/admin/settlements/rerun)의 몫이다.
 *
 * <p>{@code date} 를 미래로 주면 그날 풀릴 물량을 미리 점검할 수 있다(자금 계획).
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/settlements/**} 매처(ADMIN)로 제한된다.
 */
@RestController
@RequestMapping("/admin/settlements")
public class HoldbackPreviewAdminController {

    private static final int DEFAULT_LIMIT = 200;

    private final ReleaseHoldbackUseCase useCase;

    public HoldbackPreviewAdminController(ReleaseHoldbackUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "홀드백 해제 미리보기 — 그날 무엇이 얼마나 풀리는지",
            description = "아무 상태도 바꾸지 않는다. limit 까지 가득 차면 truncated=true 로 "
                    + "'이게 전부가 아님'을 알린다.")
    @GetMapping("/holdback-preview")
    public ResponseEntity<HoldbackReleasePreview> preview(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok(
                useCase.previewReleasableOn(date == null ? LocalDate.now() : date, limit));
    }
}
