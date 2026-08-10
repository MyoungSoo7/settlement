package github.lms.lemuel.closing.application.dto;

import java.math.BigDecimal;

/** 집계 어댑터가 반환하는 셀러 월 집계 원시 행 — 도메인 검증 전 상태(검증은 서비스에서 도메인 팩토리로). */
public record SellerAggregateRow(Long sellerId, long settlementCount,
                                 BigDecimal grossAmount, BigDecimal refundedAmount,
                                 BigDecimal commissionAmount, BigDecimal holdbackAmount,
                                 BigDecimal netAmount) {
}
