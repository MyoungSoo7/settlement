package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.Order;

import java.util.List;

/**
 * 회수 대기 후보 조회 포트.
 *
 * <p>어댑터는 인덱스로 좁힐 수 있는 조건(배송됨 · 미원복 · 종단)까지만 거른다 — 최종 판정은
 * {@link Order#isAwaitingStockReclaim()} 가 하므로, 쿼리 조건이 도메인 규칙과 어긋나도
 * 잘못된 건이 화면에 오르지 않는다.
 */
public interface LoadPendingStockReclaimPort {

    List<Order> findAwaitingStockReclaim(int limit);
}
