package github.lms.lemuel.projectionbackfill;

/**
 * settlement 로컬 프로젝션 백필 (ADR 0020 Phase 4 Chunk 3).
 *
 * <p>물리 분리(settlement_db) 컷오버 시 settlement 의 프로젝션(payment/order/user/product_view)은
 * 신규 이벤트만 채운다. 기존 order 데이터를 settlement 로 시드하기 위해, order-service 가 모든 기존
 * 엔티티를 도메인 이벤트로 <b>재발행</b>한다(멱등 — 컨슈머 upsert + processed_events). reservation 의
 * 기사 프로젝션 백필과 동형.
 */
public interface BackfillSettlementProjectionsUseCase {

    BackfillResult backfillAll();

    record BackfillResult(int users, int products, int orders, int payments) {}
}
