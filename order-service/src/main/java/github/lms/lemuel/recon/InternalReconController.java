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

    @Operation(summary = "일일 대사 건수 (order 원천) — INV-9 건수 축",
            description = "해당 날짜 캡처 건수(캡처 이력 기준)·COMPLETED 환불 건수(완료일 기준). 금액 합계 대사의 ±상쇄 사각지대 보완")
    @GetMapping("/daily-counts")
    public DailyCounts dailyCounts(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new DailyCounts(
                repository.countCapturedPayments(date),
                repository.countCompletedRefunds(date));
    }

    @Operation(summary = "기간 COMPLETED 환불 목록 (완료일 기준) — INV-8 지연 환불 조정 대사용",
            description = "refund id·payment id·금액·완료일. settlement 가 settlement_adjustments.refund_id 와 대조해 조정 누락을 감지")
    @GetMapping("/refunds-completed")
    public List<ReconQueryRepository.CompletedRefundRow> refundsCompleted(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1000") int limit) {
        return repository.listCompletedRefunds(from, to, Math.min(limit, 5000));
    }

    @Operation(summary = "환불 id 집합의 COMPLETED 합계",
            description = "settlement 의 조정(settlement_adjustments)이 참조하는 refund_id 들의 COMPLETED 환불 합계 — cross-DB JOIN 분해용")
    @PostMapping("/refunds-completed-sum")
    public AmountResponse refundsCompletedSum(@RequestBody RefundIdsRequest request) {
        return new AmountResponse(repository.sumCompletedRefundsByIds(request.refundIds()));
    }

    @Operation(summary = "일일 캡처 결제 키셋 체크섬 — INV-12 프로젝션 diff 1차 스크리닝",
            description = "count·금액합·정렬 id md5 3-스칼라만 반환. settlement 프로젝션과 이 셋이 어긋날 때만 payment-keys 로 행 diff")
    @GetMapping("/payment-keys-checksum")
    public ReconQueryRepository.PaymentKeyChecksum paymentKeyChecksum(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return repository.paymentKeyChecksum(date);
    }

    @Operation(summary = "일일 캡처 결제 키 페이지 (INV-12 diff 용)",
            description = "id 키셋 페이지네이션 — afterId 초과분 (id, amount) 목록. PII 없음(키+금액만). limit 상한 2000")
    @GetMapping("/payment-keys")
    public List<ReconQueryRepository.PaymentKeyRow> paymentKeys(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "1000") int limit) {
        return repository.listPaymentKeys(date, afterId, Math.min(Math.max(limit, 1), 2000));
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

    public record DailyCounts(long capturedCount, long completedRefundsCount) {
    }

    public record PeriodTotals(BigDecimal capturedPayments, BigDecimal completedRefunds,
                               long paymentCapturedPublishedCount) {
    }

    public record RefundIdsRequest(List<Long> refundIds) {
    }

    public record AmountResponse(BigDecimal amount) {
    }
}
