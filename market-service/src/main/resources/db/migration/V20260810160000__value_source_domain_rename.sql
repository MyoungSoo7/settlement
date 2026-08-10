-- V20260810160000: stock_quotes.source 를 도메인 언어로 재정의 (SEED→SAMPLE, KRX→EXCHANGE)
--
-- [왜 바꾸나]
--   ValueSource 는 도메인 enum 인데 값이 도메인 언어가 아니었다. SEED 는 Flyway 시드라는 *적재 수단*
--   이고, KRX 는 *특정 공급자 이름* 이다. 공급자가 바뀌면 도메인 상수가 거짓이 된다.
--   도메인이 알아야 할 건 "이 값을 믿고 계산해도 되는가" 뿐이라
--   SAMPLE(근사 샘플, 신뢰 불가) / EXCHANGE(거래소 공시 실시세, 신뢰 가능) 로 바꾼다.
--   어느 공급자를 통해 받았는지는 어댑터의 사정이라 도메인에 담지 않는다.
--
-- [순서가 중요하다]
--   chk_sq_source 가 값 집합을 ('SEED','KRX') 로 고정하고 있어, UPDATE 가 먼저 오면
--   CHECK 위반으로 전량 실패한다. 반드시 제약 해제 → 값 치환 → 새 집합으로 재생성 순서.
--
-- [파티션드 테이블 주의]
--   stock_quotes 는 base_date RANGE 파티션드 테이블이다(V20260715130300).
--   CHECK 은 부모에 정의돼 파티션이 상속하므로 부모에서 DROP/ADD 하면 전 파티션에 전파된다.
--   source 는 파티션 키가 아니므로 UPDATE 로 행이 파티션 간 이동하지 않는다.
--
-- [길이]
--   source 는 VARCHAR(10). 'EXCHANGE'(8) / 'SAMPLE'(6) 둘 다 들어간다.

ALTER TABLE stock_quotes DROP CONSTRAINT chk_sq_source;

UPDATE stock_quotes SET source = 'SAMPLE' WHERE source = 'SEED';
UPDATE stock_quotes SET source = 'EXCHANGE' WHERE source = 'KRX';

ALTER TABLE stock_quotes
    ADD CONSTRAINT chk_sq_source CHECK (source IN ('SAMPLE', 'EXCHANGE'));
