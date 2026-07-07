package github.lms.lemuel.integrity.application.port.in;

import github.lms.lemuel.integrity.domain.HoldbackStatusReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.integrity.domain.ProcessedEventCount;
import github.lms.lemuel.integrity.domain.RefundAdjustmentReport;
import github.lms.lemuel.integrity.domain.StuckStateReport;

import java.time.LocalDate;
import java.util.List;

/**
 * 정합성 검증(Integrity Suite Phase A) 조회 유스케이스 — 전부 읽기 전용.
 *
 * <p>설계: docs/design/settlement-integrity-suite.md §3.1. 탐지까지만 담당하며
 * 정정은 기존 운영 경로(조정/역분개/DLT 리플레이)로만 한다.
 */
public interface IntegrityQueryUseCase {

    /** INV-5 — 확정 정산·환불 조정 ↔ 원장 분개 완전성. */
    LedgerCompletenessReport checkLedgerCompleteness(LocalDate date, Integer graceMinutesOverride);

    /** INV-6 — 그날 확정 정산 ↔ payout 금액·중복 대사. */
    PayoutReconReport checkPayoutRecon(LocalDate date);

    /** INV-7 — 해제 기한 경과 홀드백. */
    HoldbackStatusReport checkHoldbackStatus();

    /** INV-11 — 중간 상태 장기 체류. */
    StuckStateReport checkStuckStates(Integer thresholdMinutesOverride);

    /** INV-8 — 완료 환불(완료일 기준) ↔ 조정(역정산) 존재 대사. 지연 환불의 조정 누락 감지. */
    RefundAdjustmentReport checkRefundAdjustments(LocalDate from, LocalDate to);

    /** INV-10 — 소비측 이벤트 회계 분자 (processed_events 그룹별 건수). 판정은 MCP event_accounting. */
    List<ProcessedEventCount> processedEventCounts(LocalDate from, LocalDate to);
}
