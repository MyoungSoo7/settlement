package github.lms.lemuel.recon;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * order 가 자기 소유 데이터(opslab)의 대사용 합계/행을 노출하는 내부 API (ADR 0020 Phase 5 self-totals).
 *
 * <p>settlement 의 일일/기간/PG 대사가 이 API 를 호출해 자기 settlement_db 숫자와 비교한다.
 * 양측 모두 자기 DB 만 읽으므로 cross-DB 연결이 사라진다. ADMIN/내부 전용(SecurityConfig 강제).
 */
@Tag(name = "Internal - Reconciliation", description = "order 자기 데이터 대사 합계 노출 (settlement 가 소비)")
@RestController
@RequestMapping("/internal/recon")
public class InternalReconController {

    private final ReconQueryRepository repository;

    public InternalReconController(ReconQueryRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "일일 대사 합계 (order 원천)",
            description = "해당 날짜 캡처 gross(CAPTURED+REFUNDED)·완료 환불(완료일 기준)·캡처일 기준 반영 환불 합계")
    @GetMapping("/daily-totals")
    public DailyTotals dailyTotals(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new DailyTotals(
                repository.sumCapturedPayments(date),
                repository.sumCompletedRefunds(date),
                repository.sumRefundedAgainstCaptures(date));
    }

    @Operation(summary = "기간 대사 합계 (order 원천)",
            description = "기간 내 CAPTURED 결제·COMPLETED 환불 합계 + PaymentCaptured PUBLISHED 건수")
    @GetMapping("/period-totals")
    public PeriodTotals periodTotals(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new PeriodTotals(
                repository.sumCapturedPayments(from, to),
                repository.sumCompletedRefunds(from, to),
                repository.countPaymentCapturedPublished(from, to));
    }

    @Operation(summary = "환불 id 집합의 COMPLETED 합계",
            description = "settlement 의 조정(settlement_adjustments)이 참조하는 refund_id 들의 COMPLETED 환불 합계 — cross-DB JOIN 분해용")
    @PostMapping("/refunds-completed-sum")
    public AmountResponse refundsCompletedSum(@RequestBody RefundIdsRequest request) {
        return new AmountResponse(repository.sumCompletedRefundsByIds(request.refundIds()));
    }

    @Operation(summary = "영업일 결제 행 (PG 대사용)", description = "CAPTURED/REFUNDED 이면서 pg_transaction_id 보유분")
    @GetMapping("/captured-payments")
    public List<ReconQueryRepository.ReconPaymentRow> capturedPayments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return repository.loadCapturedPaymentRows(date);
    }

    public record DailyTotals(BigDecimal capturedPayments, BigDecimal completedRefunds,
                              BigDecimal refundedAgainstCaptures) {
    }

    public record PeriodTotals(BigDecimal capturedPayments, BigDecimal completedRefunds,
                               long paymentCapturedPublishedCount) {
    }

    public record RefundIdsRequest(List<Long> refundIds) {
    }

    public record AmountResponse(BigDecimal amount) {
    }
}
