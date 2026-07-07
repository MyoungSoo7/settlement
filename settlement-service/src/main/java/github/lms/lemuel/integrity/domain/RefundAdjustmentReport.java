package github.lms.lemuel.integrity.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * INV-8 지연 환불 조정 대사 리포트 — "COMPLETED 환불에는 조정(역정산)이 반드시 존재한다".
 *
 * <p>일일 대사의 환불 축은 <b>캡처일</b> 기준이라, 과거 캡처 건에 대해 오늘 완료된 환불
 * (지연 환불)은 당일 대사에 잡히지 않는다. 이 리포트는 환불 <b>완료일</b> 기준으로 order 의
 * COMPLETED 환불 목록을 받아 settlement_adjustments.refund_id 와 정면 대조한다.
 *
 * <p>조정 생성은 이벤트 경유 비동기이므로, 조회 기간의 끝({@code to})은 어제 이전을 권장한다
 * (당일 환불은 컨슈머 처리 중일 수 있음 — 오탐 방지는 기간 선택으로 담보).
 */
public record RefundAdjustmentReport(
        LocalDate from,
        LocalDate to,
        long completedRefunds,             // order: 기간 내 COMPLETED 환불 건수 (조회 상한 절단 반영)
        BigDecimal completedRefundTotal,   // 그 환불 금액 합
        long adjustedRefunds,              // settlement: 조정이 존재하는 환불 건수
        List<Long> missingRefundIds,       // 조정이 없는 refund_id (상한 절단)
        BigDecimal missingAmountTotal,     // 조정 누락 환불 금액 합 — 새고 있는 돈의 총량
        boolean truncated,                 // order 목록이 limit 에서 절단됐는지 (완전성 주의 신호)
        boolean ok,
        List<String> reasons
) {

    public static RefundAdjustmentReport of(LocalDate from, LocalDate to,
                                            long completedRefunds,
                                            BigDecimal completedRefundTotal,
                                            long adjustedRefunds,
                                            List<Long> missingRefundIds,
                                            BigDecimal missingAmountTotal,
                                            boolean truncated) {
        List<String> reasons = new ArrayList<>();
        if (!missingRefundIds.isEmpty()) {
            reasons.add("COMPLETED 환불 " + missingRefundIds.size() + "건에 조정(역정산)이 없습니다 — 합계 "
                    + missingAmountTotal + " 과지급 위험 (INV-8 위반)");
        }
        if (truncated) {
            reasons.add("order 환불 목록이 조회 상한에서 절단됨 — 기간을 좁혀 재검하세요 (완전 검사 아님)");
        }
        return new RefundAdjustmentReport(from, to, completedRefunds, completedRefundTotal,
                adjustedRefunds, List.copyOf(missingRefundIds), missingAmountTotal, truncated,
                missingRefundIds.isEmpty(), List.copyOf(reasons));
    }
}
