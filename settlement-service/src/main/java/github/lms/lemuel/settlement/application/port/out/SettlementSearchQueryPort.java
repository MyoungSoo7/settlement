package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.ApprovalStatusDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementCursorPageResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementDetailDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementSearchCondition;

import java.util.List;

/**
 * 정산 상세 탐색 역할 — 조건 검색·승인상태 페이지·결제단위 감사추적 (Read Model).
 *
 * <p>{@link QuerySettlementPort} 의 응집 축 중 하나(상세 조회/드릴다운). 탐색만 쓰는 소비처는 이
 * 역할만 의존하면 된다(ISP).
 */
public interface SettlementSearchQueryPort {

    SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition);

    SettlementCursorPageResponse<ApprovalStatusDto> findByApprovalStatus(String status, int size, Long cursorId);

    List<SettlementDetailDto> findAuditTrailByPaymentId(Long paymentId);
}
