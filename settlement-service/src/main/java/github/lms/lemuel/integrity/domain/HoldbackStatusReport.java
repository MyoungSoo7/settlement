package github.lms.lemuel.integrity.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * INV-7 홀드백 리포트 — "해제일이 지난 보류금은 반드시 풀려 있어야 한다".
 *
 * <p>HoldbackReleaseScheduler(매일 03:00)가 조용히 죽으면 셀러 돈이 무기한 묶이지만
 * 아무 대사에도 걸리지 않는다. 이 리포트가 그 침묵 실패를 정면 감지한다.
 * 해제일 당일({@code releaseDate == today})은 배치가 아직 안 돌았을 수 있으므로
 * overdue 는 {@code releaseDate < today} 만 센다.
 */
public record HoldbackStatusReport(
        LocalDate today,
        long overdueCount,                 // 해제일 경과 & 미해제
        BigDecimal overdueAmountTotal,
        List<Long> overdueSettlementIds,   // 상한 절단
        BigDecimal totalHeld,              // 미해제 보류금 합 (overdue + 아직 기한 전)
        BigDecimal totalReleased,          // 해제 완료된 보류금 합
        LocalDateTime lastReleasedAt,      // 마지막 해제 시각 — 배치 생존 신호
        boolean ok,
        List<String> reasons
) {

    public static HoldbackStatusReport of(LocalDate today,
                                          long overdueCount,
                                          BigDecimal overdueAmountTotal,
                                          List<Long> overdueSettlementIds,
                                          BigDecimal totalHeld,
                                          BigDecimal totalReleased,
                                          LocalDateTime lastReleasedAt) {
        List<String> reasons = new ArrayList<>();
        if (overdueCount > 0) {
            reasons.add("해제일이 지났는데 미해제인 홀드백 " + overdueCount + "건, 합계 "
                    + overdueAmountTotal + " — HoldbackReleaseScheduler 정지 의심 (INV-7 위반)");
        }
        return new HoldbackStatusReport(today, overdueCount, overdueAmountTotal,
                List.copyOf(overdueSettlementIds), totalHeld, totalReleased, lastReleasedAt,
                reasons.isEmpty(), List.copyOf(reasons));
    }
}
