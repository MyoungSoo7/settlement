package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.ReputationResponse;
import github.lms.lemuel.company.application.port.in.RecalcReputationUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 평판 재계산 트리거 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>외부 호출 없이 DB 읽기 + 인메모리 분류라 동기 실행하고 결과를 바로 돌려준다(수집과 달리
 * 장시간 배치가 아님). INSERT-only 이므로 오늘자 스냅샷이 이미 있으면 건너뛴다.
 */
@RestController
@RequestMapping("/admin/company/reputation")
public class ReputationAdminController {

    private final RecalcReputationUseCase recalcReputationUseCase;

    public ReputationAdminController(RecalcReputationUseCase recalcReputationUseCase) {
        this.recalcReputationUseCase = recalcReputationUseCase;
    }

    @PostMapping("/recalc")
    public ResponseEntity<RecalcReputationUseCase.RecalcSummary> recalcAll() {
        return ResponseEntity.ok(recalcReputationUseCase.recalcAll());
    }

    @PostMapping("/recalc/{stockCode}")
    public ResponseEntity<?> recalcOne(@PathVariable String stockCode) {
        return recalcReputationUseCase.recalcFor(stockCode)
                .<ResponseEntity<?>>map(score -> ResponseEntity.ok(ReputationResponse.from(score)))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "message", "스냅샷 미생성 — 기사가 없거나 오늘자 스냅샷이 이미 있습니다: " + stockCode)));
    }
}
