package github.lms.lemuel.recon;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * settlement 가 자기 소유 데이터의 대사용 행을 노출하는 내부 API.
 *
 * <p>order 의 {@code /internal/recon/*}(자기 데이터 노출)와 대칭인 짝이다. 이 엔드포인트가 없어서
 * reconciliation-service 의 EXPECTED(정산 관점) 소스가 구현되지 못했고, 그 결과 대사 배치가
 * 번들 샘플 데이터로 매일 같은 가짜 결과를 냈다(2026-07-30 확인).
 *
 * <p>인증은 shared-common 의 {@code InternalApiKeyFilter}(X-Internal-Api-Key) 가 담당한다.
 */
@Tag(name = "Internal - Reconciliation", description = "settlement 자기 데이터 대사 행 노출 (reconciliation-service 가 소비)")
@RestController
@RequestMapping("/internal/recon")
public class InternalSettlementReconController {

    private final SettlementReconQueryRepository repository;

    public InternalSettlementReconController(SettlementReconQueryRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "캡처일 기준 정산 행 (settlement 원천)",
            description = "payment_id · (결제금액-환불금액) · PAID/REFUNDED. 상대편 order 의 captured-payments 와 "
                    + "같은 기준일(캡처일)·같은 금액 정의라 그대로 대사할 수 있다. settlement_date(T+1 지급예정일) 아님.")
    @GetMapping("/settlements")
    public List<SettlementReconQueryRepository.SettlementReconRow> settlements(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "5000") int limit) {
        return repository.listByCapturedDate(date, limit);
    }
}
