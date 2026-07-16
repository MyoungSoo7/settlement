package github.lms.lemuel.integrity.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * INV-12 프로젝션 행 diff 리포트 — "order 원천 행 집합 = settlement_*_view 행 집합 (id 단위)".
 *
 * <p>기존 프로젝션 게이지는 합계(rows/amount)만 봐서, 어떤 행이 누락/고아인지 특정하지 못했다.
 * 이 리포트는 <b>하이브리드 대사</b>로 그 사각지대를 메운다(설계서 §5): 먼저 양측이 자기 DB 에서
 * 계산한 키셋 체크섬(count·금액합·정렬 id md5)을 대조하고({@link #matched}), 어긋날 때만 실제 키
 * 목록을 페이지네이션으로 당겨 <b>누락 id 를 특정</b>한다({@link #of}). 건강한 날은 3-스칼라만 교환해
 * 데이터량 리스크를 회피하고, 불일치 날만 행 단위 비용을 치른다.
 *
 * <p>탐지까지만 담당한다 — 정정은 기존 운영 경로(projectionbackfill 재적재)로만 하고 DB 직접 수정은 금지.
 */
public record ProjectionDiffReport(
        LocalDate date,
        String entity,                              // 대사 대상 프로젝션 (현재 "payment")
        boolean checksumMatched,                    // 1차 체크섬이 일치했는가 (true 면 행 diff 생략)
        long orderCount,                            // order 원천 결제 건수
        BigDecimal orderAmountSum,                  // order 원천 금액 합
        long projectionCount,                       // 프로젝션 결제 건수
        BigDecimal projectionAmountSum,             // 프로젝션 금액 합
        long missingInProjectionCount,             // order 엔 있고 프로젝션엔 없는 행 수 (누락 — 삭제/유실)
        List<Long> missingInProjectionIds,          // 그 payment_id 상위 N 건 (누락 특정)
        BigDecimal missingInProjectionAmount,       // 누락 행 금액 합 — 안 보이는 돈의 총량
        long orphanInProjectionCount,              // 프로젝션엔 있고 order 엔 없는 행 수 (고아)
        List<Long> orphanInProjectionIds,           // 그 payment_id 상위 N 건
        long amountMismatchCount,                   // id 는 있으나 금액이 다른 행 수
        List<AmountMismatch> amountMismatches,      // 금액 불일치 상위 N 건 (양측 값 병기)
        boolean truncated,                          // 키 수집이 상한에서 절단됐는지 (완전 검사 아님 신호)
        boolean ok,
        List<String> reasons
) {

    /** id 는 양측에 있으나 금액이 다른 행 (order 값 vs 프로젝션 값 병기). */
    public record AmountMismatch(long paymentId, BigDecimal orderAmount, BigDecimal projectionAmount) {
    }

    /** 1차 체크섬 일치 — 행 diff 없이 통과. count/amountSum 은 양측 동일하므로 order 값으로 채운다. */
    public static ProjectionDiffReport matched(LocalDate date, String entity,
                                               long count, BigDecimal amountSum) {
        return new ProjectionDiffReport(date, entity, true,
                count, amountSum, count, amountSum,
                0L, List.of(), BigDecimal.ZERO,
                0L, List.of(),
                0L, List.of(),
                false, true, List.of());
    }

    /** 체크섬 불일치 → 실제 키 diff 결과로 조립. ok/reasons 는 발견된 위반으로부터 파생. */
    public static ProjectionDiffReport of(LocalDate date, String entity,
                                          long orderCount, BigDecimal orderAmountSum,
                                          long projectionCount, BigDecimal projectionAmountSum,
                                          List<Long> missingIds, BigDecimal missingAmount,
                                          long missingTotal,
                                          List<Long> orphanIds, long orphanTotal,
                                          List<AmountMismatch> amountMismatches, long amountMismatchTotal,
                                          boolean truncated) {
        List<String> reasons = new ArrayList<>();
        if (missingTotal > 0) {
            reasons.add("프로젝션 누락 " + missingTotal + "건 — order 엔 있으나 " + entity
                    + " 뷰에 없는 결제 (합계 " + missingAmount + ", 프로젝션 유실/삭제 의심, INV-12 위반). "
                    + "재적재는 projectionbackfill 로만");
        }
        if (orphanTotal > 0) {
            reasons.add("프로젝션 고아 " + orphanTotal + "건 — " + entity
                    + " 뷰엔 있으나 order 원천에 없는 결제 (이중 적재/원천 삭제 의심, INV-12 위반)");
        }
        if (amountMismatchTotal > 0) {
            reasons.add("금액 불일치 " + amountMismatchTotal + "건 — id 는 있으나 프로젝션 금액이 order 와 다름 (드리프트)");
        }
        if (truncated) {
            reasons.add("키 수집이 상한에서 절단됨 — 완전 검사가 아니니 범위를 좁혀 재검하세요");
        }
        boolean ok = missingTotal == 0 && orphanTotal == 0 && amountMismatchTotal == 0;
        return new ProjectionDiffReport(date, entity, false,
                orderCount, orderAmountSum, projectionCount, projectionAmountSum,
                missingTotal, List.copyOf(missingIds), missingAmount,
                orphanTotal, List.copyOf(orphanIds),
                amountMismatchTotal, List.copyOf(amountMismatches),
                truncated, ok, List.copyOf(reasons));
    }
}
