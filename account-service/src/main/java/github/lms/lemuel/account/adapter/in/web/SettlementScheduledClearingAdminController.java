package github.lms.lemuel.account.adapter.in.web;

import github.lms.lemuel.account.application.port.in.ClearScheduledResidualUseCase;
import github.lms.lemuel.account.application.port.in.ClearScheduledResidualUseCase.ClearingReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * cut-over 잔존 정산예정금 청산 백필 관리자 API — ADR 0026 Option A.
 *
 * <p>경로 {@code /admin/backfill/**} 는 shared-common SecurityConfig 에서 ADMIN 권한으로 게이트된다.
 * 멱등이라 반복 호출해도 결과가 불변이다(추가 청산 0건).
 */
@RestController
@RequestMapping("/admin/backfill")
public class SettlementScheduledClearingAdminController {

    private final ClearScheduledResidualUseCase clearScheduledResidualUseCase;

    public SettlementScheduledClearingAdminController(ClearScheduledResidualUseCase clearScheduledResidualUseCase) {
        this.clearScheduledResidualUseCase = clearScheduledResidualUseCase;
    }

    @PostMapping("/settlement-scheduled-clearing")
    public ResponseEntity<ClearingReport> clearScheduledResidual() {
        return ResponseEntity.ok(clearScheduledResidualUseCase.clearResidual());
    }
}
