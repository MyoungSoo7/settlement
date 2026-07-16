-- V20260716600000: investment_orders 낙관적 락(@Version) 컬럼 추가 (동시성 감사 보강, E4 레인)
--
-- 배경(감사 지적): investment_orders 는 execute/cancel 상태 전이를 load→mutate→save 로 수행하지만
--   행 보호 장치가 없어, 같은 주문에 execute·cancel 동시 진입 시 lost update(이중 집행/이중 이벤트,
--   또는 취소가 집행을 덮어씀)가 가능했다. JPA @Version 낙관적 락으로 두 번째 커밋을
--   ObjectOptimisticLockingFailureException 으로 실패시켜 정확히 한쪽만 성공하도록 직렬화한다.
--   (셀러 단위 재원 write-skew 는 seller_funding_view 행 SELECT ... FOR UPDATE 로 별도 직렬화 —
--    쿼리 레벨 락이라 스키마 변경 불요.)
--
-- 제약: 기존 행은 DEFAULT 0 으로 백필된다. 엔티티(InvestmentOrderJpaEntity)가 @Version Long version 으로
--   매핑하므로 BIGINT NOT NULL 로 둔다(ddl-auto=validate 통과). corporate_loans 는 실행 전 비관적 락
--   (findByIdForUpdate)을 쓰지만, investment 는 재원 락과 조합해야 하므로 주문 행은 낙관적 락을 택했다.

ALTER TABLE investment_orders
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN investment_orders.version IS
    'JPA @Version 낙관적 락 카운터 — execute/cancel 동시 진입 시 lost update 차단(둘째 커밋 실패).';
