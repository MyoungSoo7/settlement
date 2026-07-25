package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.ReputationResponse;
import github.lms.lemuel.company.application.port.in.RecalcReputationUseCase;
import github.lms.lemuel.company.audit.application.port.out.RecordAuditPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 평판 재계산 트리거 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>신규 기사에 대해 감성분석(provider=gemini 면 Gemini 외부 호출, 실패·지연 시 키워드 폴백)을
 * 순차 수행 후 결과를 돌려주는 동기 배치다. 각 호출은 {@code HttpClientConfig} 의 read 타임아웃으로
 * 유계라, 느린 LLM 응답에도 총 소요가 폭주하지 않는다(2026-07-19/20 배치 타임아웃 재발 방지).
 * INSERT-only 이므로 오늘자 스냅샷이 이미 있으면 건너뛴다.
 */
@RestController
@RequestMapping("/admin/company/reputation")
public class ReputationAdminController {

    private final RecalcReputationUseCase recalcReputationUseCase;
    private final RecordAuditPort recordAuditPort;

    public ReputationAdminController(RecalcReputationUseCase recalcReputationUseCase,
                                     RecordAuditPort recordAuditPort) {
        this.recalcReputationUseCase = recalcReputationUseCase;
        this.recordAuditPort = recordAuditPort;
    }

    @PostMapping("/recalc")
    public ResponseEntity<RecalcReputationUseCase.RecalcSummary> recalcAll() {
        recordAuditPort.record("REPUTATION_RECALC_TRIGGERED", "Reputation", "ALL", Map.of());
        return ResponseEntity.ok(recalcReputationUseCase.recalcAll());
    }

    @PostMapping("/recalc/{stockCode}")
    public ResponseEntity<?> recalcOne(@PathVariable String stockCode) {
        recordAuditPort.record("REPUTATION_RECALC_TRIGGERED", "Reputation", stockCode, Map.of());
        return recalcReputationUseCase.recalcFor(stockCode)
                .<ResponseEntity<?>>map(score -> ResponseEntity.ok(ReputationResponse.from(score)))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "message", "스냅샷 미생성 — 기사가 없거나 오늘자 스냅샷이 이미 있습니다: " + stockCode)));
    }
}
