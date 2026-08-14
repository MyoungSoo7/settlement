-- 셀러 등급 통지 반영 컬럼 (ADR 0031 §4) + updated_at 시간대 표준화(N1)
--
-- settlement_user_view 는 settlement 소유 프로젝션이다(ADR 0020). lemuel.seller.tier_changed 를
-- 소비해 여기에 등급을 적재한다 — 용도는 운영 조회·리포트뿐이다.
--
-- ★ 정산 계산에 쓰지 말 것: 정산은 결제 시점 등급(settlement_payment_view.seller_tier, PaymentCaptured
--   동봉값)을 쓴다. 이 컬럼을 계산에 끌어다 쓰면 등급 변경이 과거 정산까지 소급 재해석한다.
--
-- 무행동 착지: 기존 행의 등급은 NULL 로 남고, 첫 등급 변경 통지가 오기 전까지 어떤 동작도 달라지지 않는다.
ALTER TABLE public.settlement_user_view
    ADD COLUMN IF NOT EXISTS seller_tier varchar(20),
    ADD COLUMN IF NOT EXISTS tier_effective_from date;

-- updated_at 은 "적재된 순간"이라 시간대가 붙어야 한다(DATA-STANDARD N1). 기존 값은 LocalDateTime.now()
-- 로 쓰인 Asia/Seoul 벽시계이므로 그 시간대로 해석해 변환한다 — USING 없이 캐스팅하면 서버 timezone
-- 설정에 따라 값이 조용히 9시간 밀린다.
ALTER TABLE public.settlement_user_view
    ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'Asia/Seoul';
