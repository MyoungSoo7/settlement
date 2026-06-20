package github.lms.lemuel.report.application.port.out;

import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowBucket;

import java.time.LocalDate;
import java.util.List;

public interface LoadCashflowAggregatePort {

    /**
     * 지정된 기간·그룹 기준으로 settlements 테이블을 집계한다.
     * 반환 리스트는 bucket 날짜 오름차순.
     *
     * <p>Granularity 별 bucket 기준일:
     * <ul>
     *   <li>DAY   — settlement_date 그대로</li>
     *   <li>WEEK  — 해당 주 월요일 (ISO week, date_trunc('week'))</li>
     *   <li>MONTH — 해당 월 1일 (date_trunc('month'))</li>
     * </ul>
     */
    List<CashflowBucket> aggregate(LocalDate from, LocalDate to, BucketGranularity granularity);

    /**
     * 판매자 단위 집계 — settlements JOIN payments JOIN orders JOIN products 로
     * {@code products.seller_id = ?} 필터 후 동일한 bucket 집계를 수행한다.
     *
     * @param sellerId 대상 판매자 ID (NULL 불가)
     */
    List<CashflowBucket> aggregateBySeller(LocalDate from, LocalDate to,
                                           BucketGranularity granularity, Long sellerId);
}
