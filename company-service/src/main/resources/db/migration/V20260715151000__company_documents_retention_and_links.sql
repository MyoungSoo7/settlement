-- V20260715151000: 문서함 BYTEA 성장 방어 + 셀러 링크 경계 문서화 — 3인 DB 리뷰 지적 반영
--
-- 설계 근거:
--   (2) company_documents.content 는 파일 바이트를 BYTEA 로 DB 에 직접 적재한다(V6). 도메인이 20MB
--       상한을 강제하지만 DB 계층 방어선이 없어, 도메인 우회 경로(직접 INSERT·배치)로 대용량 바이트가
--       유입되면 볼륨·백업이 폭증할 수 있다는 지적. → octet_length 기반 CHECK 로 심층 방어하고,
--       나이 기반 아카이빙/파기 스캔용 인덱스를 uploaded_at 단독으로 보강한다(V6 의 (stock_code,
--       uploaded_at) 복합 인덱스는 기업별 조회용이라 전역 나이 스캔에는 비효율).
--   (3) seller_company_links(=company_seller_links) 의 내부 FK/UNIQUE 누락 점검 결과: stock_code→
--       companies FK 와 seller_id PK(1셀러 1기업)는 이미 갖춰져 있다. seller_id→company_sellers FK 는
--       "의도적으로" 걸지 않는다 — company_sellers 는 user.registered 프로젝션으로 채워지고 admin 링크는
--       그와 독립 시점에 생성되므로(이벤트 순서 비보장), 하드 FK 는 정상 운영을 깨뜨린다. 경계를 주석으로
--       명문화하는 것이 올바른 보강이다(신규 제약 없음).

-- (2) content 바이트 상한 20MB DB 방어선 (도메인 20MB 상한과 이중화)
ALTER TABLE company_documents
    ADD CONSTRAINT chk_company_documents_content_max
    CHECK (octet_length(content) <= 20971520);  -- 20 * 1024 * 1024

-- (2) 나이 기반 아카이빙/파기 스캔용 인덱스 (전 기업 횡단, uploaded_at 단독)
CREATE INDEX IF NOT EXISTS idx_company_documents_uploaded
    ON company_documents (uploaded_at);

COMMENT ON COLUMN company_documents.content IS
    'BYTEA 원본 바이트. ⚠️ 누적 성장 리스크 — 문서 수/크기 증가 시 DB 볼륨·백업 부담이 선형 증가. '
    '향후 오브젝트 스토리지(S3 등) 이관 + content 를 참조 키/URL 컬럼으로 대체 계획. '
    '20MB 상한은 도메인 + chk_company_documents_content_max 로 이중 강제, '
    'idx_company_documents_uploaded 로 오래된 문서 파기 스캔.';

-- (3) 셀러↔기업 링크 FK 경계 명문화 (신규 제약 없음 — 의도적 경계)
COMMENT ON TABLE company_seller_links IS
    '셀러↔기업(종목코드) 명시 링크. stock_code→companies FK 는 강제, seller_id PK 로 1셀러 1기업. '
    'seller_id 는 order-service users 의 비즈니스 키 참조이며 company_sellers 에 대한 FK 는 의도적 미설정 '
    '— company_sellers(user.registered 프로젝션)와 admin 링크는 이벤트 순서가 비보장이라 하드 FK 가 정상 경로를 깨뜨린다.';
COMMENT ON TABLE company_sellers IS
    'user.registered 로 수신한 셀러 등록 목록(sellerId PK 멱등). company_seller_links 의 링크 대상이며, '
    'FK 로 강제하지 않고 비즈니스 키(seller_id)로만 연계한다(DB-per-service · 이벤트 순서 비보장).';
