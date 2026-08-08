package github.lms.lemuel.closing.application.dto;

import java.util.List;

/**
 * 월 집계 스냅샷 — 셀러별 DONE 정산 집계 + 마감 품질 카운트.
 *
 * @param rows          셀러별 집계 행(셀러 매핑 성공한 DONE 정산만)
 * @param unmappedCount 프로젝션에 셀러가 없어 마트에서 빠진 DONE 정산 건수(0 이 정상)
 * @param pendingCount  아직 미확정(REQUESTED/PROCESSING) 정산 건수 — 마감 후 유입될 수 있는 양
 */
public record MonthlyAggregateSnapshot(List<SellerAggregateRow> rows,
                                       long unmappedCount, long pendingCount) {
}
