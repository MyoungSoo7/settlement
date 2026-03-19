package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * Cursor 기반 페이지네이션 응답
 *
 * WHY cursor-based:
 * - offset pagination은 OFFSET이 커질수록 성능 저하 (full scan)
 * - 수백만 건에서 cursor(settlement_date, id) 기반이 O(log n)
 * - 실시간 데이터 삽입 시 페이지 밀림 현상 없음
 */
@Getter
@AllArgsConstructor
public class SettlementCursorPageResponse<T> {
    private final List<T> items;
    private final int size;
    private final boolean hasNext;
    private final Long nextCursorId;
    private final LocalDate nextCursorDate;
}
