package github.lms.lemuel.integrity.application.port.out;

import github.lms.lemuel.integrity.domain.HoldbackStatusReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.integrity.domain.ProcessedEventCount;
import github.lms.lemuel.integrity.domain.StuckStateReport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 정합성 집계 아웃바운드 포트 — settlement_db 자기 데이터만 읽는다 (cross-DB 0).
 *
 * <p>시각 파라미터(graceCutoff/stuckCutoff)는 서비스가 계산해 명시적으로 넘긴다 —
 * 어댑터를 시계 없는(clock-free) 결정적 쿼리로 유지해 테스트를 단순화한다.
 */
public interface IntegrityQueryPort {

    LedgerCompletenessReport ledgerCompleteness(LocalDate date, int graceMinutes, LocalDateTime graceCutoff);

    PayoutReconReport payoutRecon(LocalDate date);

    HoldbackStatusReport holdbackStatus(LocalDate today);

    StuckStateReport stuckStates(int thresholdMinutes, LocalDateTime stuckCutoff, LocalDate today);

    /** 주어진 refund_id 중 settlement_adjustments 에 조정이 존재하는 부분집합 (INV-8). */
    Set<Long> adjustedRefundIds(Collection<Long> refundIds);

    /** processed_events 를 (consumer_group, event_type) 로 묶은 건수 — 기간은 processed_at 기준 (INV-10). */
    List<ProcessedEventCount> processedEventCounts(LocalDateTime from, LocalDateTime to);

    /** 해당 날짜(captured_at 기준) settlement_payment_view 키셋 체크섬 — INV-12 1차 스크리닝. */
    KeyChecksum projectionPaymentChecksum(LocalDate date);

    /** afterId 초과 프로젝션 결제 키 페이지(payment_id 오름차순, 최대 limit 건) — INV-12 diff. */
    List<PaymentKey> projectionPaymentKeys(LocalDate date, long afterId, int limit);
}
