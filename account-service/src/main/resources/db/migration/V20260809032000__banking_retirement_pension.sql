-- V20260809032000: 퇴직연금(banking 서브도메인) 서브원장 테이블
--
-- account-service 는 지금까지 "남이 발행한 이벤트를 소비해 GL 로 집계"하기만 했다. 수신 상품
-- (정기예금·적금·퇴직연금)은 그 반대다 — 계약과 거래의 *정본* 이 계정계 안에 산다. 그래서 서브원장
-- 테이블을 여기에 두고, GL 전기는 Kafka 를 거치지 않고 인프로세스로(RecordAccountEntryUseCase)
-- 같은 트랜잭션에서 끝낸다. 서브원장과 GL 이 원자적으로 함께 움직이는 것이 이 설계의 핵심이다.
--
-- 왜 두 테이블인가:
--   retirement_pensions  : 계약 1건의 현재 상태(적립금 잔액·상태·운용지시). 갱신 대상.
--   pension_transactions : 계약에 붙는 거래 이력. append-only 이며 절대 갱신·삭제하지 않는다.
--
-- ★ seq 의 의미 — 이 마이그레이션에서 가장 중요한 제약:
--   GL 전표의 자연키는 (source_topic, ref_type, ref_id) UNIQUE 이고, 퇴직연금 전표의 ref_id 규약은
--   'RP-{pension_id}-{seq}' 다(AccountEntry.pension* 팩토리). 즉 (pension_id, seq) 가 유일하지 않으면
--   서로 다른 거래가 같은 전표 자연키로 충돌해 두 번째 거래의 GL 전기가 조용히 사라진다(ON CONFLICT
--   DO NOTHING). uq_pension_transaction_seq 는 그 사고를 서브원장 쪽에서 먼저 막는 방어선이다.
--   같은 이유로 next_seq 는 계약 행에 들고 다닌다 — 전역 시퀀스를 쓰면 계약별 단조성이 깨진다.
--
-- 제도(scheme)별 규칙은 도메인(PensionScheme)이 정본이지만, 스키마에서도 "표현 불가능"하게 닫는다.
-- 애플리케이션 우회 경로(수기 INSERT·백필 스크립트)가 도메인 규칙을 비켜 가는 것이 실제 사고 경로다:
--   - DB/DC 는 사업장이 있어야 하고 IRP 는 있으면 안 된다        → chk_pension_employer_by_scheme
--   - 중도인출은 법정 6종 사유만, DB형은 아예 불가              → chk_pension_tx_reason (+ 도메인)
--   - 부담금은 납입 주체를 반드시 동반, 그 외 거래는 반드시 null → chk_pension_tx_source
--
-- ★ 클라이언트가 정할 수 없는 값들 — birth_date 와 last_interest_settled_on 이 계약 행에 사는 이유:
--   수급요건(만 55세·가입 10년)과 이자 금액은 *서버가 계약 상태에서 파생* 해야 한다. 나이·가입기간·
--   이자금액을 요청에서 받으면 인증된 가입자가 age=60 을 보내 요건을 통과하고, 이자 금액을 보내
--   자기 적립금을 무한정 부풀린다(그대로 GL 로 전기된다). 그래서 나이의 근거(birth_date)는 가입 때
--   한 번 못 박고, 이자의 기산점(last_interest_settled_on)은 계약이 스스로 들고 다닌다.
--
-- 선행 의존: V20260809030500__account_banking_deposit_products.sql 이 account_entries 의 공유 CHECK
--   (gl account, ref_type 의 PENSION_*, owner_type 의 DEPOSITOR, owner_id 형식)를 이미 확장해 두었다.
--   그 제약들은 세 수신 상품(정기예금·적금·퇴직연금)이 함께 쓰므로 상품별 마이그레이션에서 DROP/ADD
--   하지 않는다 — 셋이 각자 확장하면 마지막 하나가 나머지의 값 집합을 덮어써 GL 전기가 조용히 막힌다.

CREATE TABLE IF NOT EXISTS retirement_pensions (
    id                 BIGSERIAL      PRIMARY KEY,
    subscriber_id      VARCHAR(64)    NOT NULL,   -- userId 숫자 문자열 (GL owner_id = DEPOSITOR 규약과 동일)
    scheme             VARCHAR(10)    NOT NULL,   -- DB(확정급여) / DC(확정기여) / IRP(개인형)
    employer_name      VARCHAR(100),              -- DB·DC 필수, IRP 는 NULL
    birth_date         DATE           NOT NULL,   -- 수급 개시 연령(만 55세) 판정 근거 — 가입 시 1회 기록
    annual_rate        NUMERIC(9, 6)  NOT NULL,   -- 계약 기준 원리금보장 운용이율 (연 3.5% = 0.035000)
    product_name       VARCHAR(100)   NOT NULL,   -- 운용지시 — 원리금보장 상품 1개(비중 배분 없는 최소 모델)
    product_rate       NUMERIC(9, 6)  NOT NULL,   -- 해당 상품의 표면이율
    status             VARCHAR(20)    NOT NULL,   -- ACCUMULATING → RECEIVING → CLOSED (단방향)
    opened_on          DATE           NOT NULL,
    last_interest_settled_on DATE,                -- 직전 이자 확정일 = 다음 이자의 기산일 (NULL 이면 개설일부터)
    benefit_started_on DATE,                      -- 수급 개시일 (개시 전 NULL)
    benefit_type       VARCHAR(20),               -- ANNUITY(연금) / LUMP_SUM(일시금)
    accumulated_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,   -- 적립금 잔액 (원 단위로 닫아 저장)
    next_seq           BIGINT         NOT NULL DEFAULT 1,   -- 다음 거래 seq (계약 단위 단조 증가)

    CONSTRAINT chk_pension_scheme        CHECK (scheme IN ('DB', 'DC', 'IRP')),
    CONSTRAINT chk_pension_status        CHECK (status IN ('ACCUMULATING', 'RECEIVING', 'CLOSED')),
    CONSTRAINT chk_pension_benefit_type  CHECK (benefit_type IS NULL OR benefit_type IN ('ANNUITY', 'LUMP_SUM')),
    CONSTRAINT chk_pension_subscriber_id CHECK (subscriber_id ~ '^[0-9]+$'),
    CONSTRAINT chk_pension_annual_rate   CHECK (annual_rate >= 0 AND annual_rate < 1),
    CONSTRAINT chk_pension_product_rate  CHECK (product_rate >= 0 AND product_rate < 1),
    CONSTRAINT chk_pension_accumulated   CHECK (accumulated_amount >= 0),
    CONSTRAINT chk_pension_next_seq      CHECK (next_seq >= 1),
    -- 생년월일은 수급요건(만 55세) 판정의 유일한 근거다. 가입일보다 미래일 수 없고, 가입 시점에
    -- 근로기준법상 취업 최저연령(만 15세) 이상이어야 한다 — "1900년생"·"내년생" 같은 조작값을
    -- 애플리케이션 우회 경로(수기 INSERT·백필)에서도 표현 불가능하게 만든다.
    CONSTRAINT chk_pension_birth_date CHECK (birth_date <= (opened_on - INTERVAL '15 years')::date),
    -- 이자 기산일은 개설일 이전으로 갈 수 없다 — 소급하면 경과일수가 늘어 없는 이자가 생긴다
    CONSTRAINT chk_pension_last_interest CHECK (
        last_interest_settled_on IS NULL OR last_interest_settled_on >= opened_on),
    -- 사업장 필수/금지를 제도와 묶어 강제 — "사업장 있는 IRP" 를 표현 불가능하게 만든다
    CONSTRAINT chk_pension_employer_by_scheme CHECK (
        (scheme IN ('DB', 'DC') AND employer_name IS NOT NULL)
        OR (scheme = 'IRP' AND employer_name IS NULL)),
    -- 수급 개시일과 수급 형태는 한 몸이다 — 한쪽만 채워진 행은 상태 전이 버그의 흔적이다
    CONSTRAINT chk_pension_benefit_pair CHECK ((benefit_started_on IS NULL) = (benefit_type IS NULL)),
    -- 수급 중이라면 반드시 개시 정보가 있어야 한다 (CLOSED 는 개시 이력을 그대로 보존)
    CONSTRAINT chk_pension_receiving_started CHECK (status <> 'RECEIVING' OR benefit_started_on IS NOT NULL)
);

-- 본인 계약 목록 조회 핫패스 (셀프서비스 API 의 유일한 목록 경로)
CREATE INDEX IF NOT EXISTS idx_retirement_pensions_subscriber
    ON retirement_pensions (subscriber_id, id);

CREATE TABLE IF NOT EXISTS pension_transactions (
    id                    BIGSERIAL      PRIMARY KEY,
    pension_id            BIGINT         NOT NULL,
    seq                   BIGINT         NOT NULL,   -- 계약 단위 거래 일련번호 = GL 전표 ref_id 의 마지막 조각
    tx_type               VARCHAR(20)    NOT NULL,   -- CONTRIBUTION / INTEREST / BENEFIT / MID_WITHDRAWAL
    amount                NUMERIC(19, 2) NOT NULL,   -- 항상 양수 (증감 방향은 tx_type 이 정한다)
    contribution_source   VARCHAR(10),               -- EMPLOYER / EMPLOYEE — CONTRIBUTION 에만
    mid_withdrawal_reason VARCHAR(40),               -- 법정 6종 — MID_WITHDRAWAL 에만
    occurred_on           DATE           NOT NULL,

    CONSTRAINT fk_pension_transaction_pension FOREIGN KEY (pension_id)
        REFERENCES retirement_pensions (id),
    CONSTRAINT chk_pension_tx_type   CHECK (tx_type IN ('CONTRIBUTION', 'INTEREST', 'BENEFIT', 'MID_WITHDRAWAL')),
    CONSTRAINT chk_pension_tx_amount CHECK (amount > 0),
    CONSTRAINT chk_pension_tx_seq    CHECK (seq >= 1),
    -- 부담금이면 납입 주체가 반드시 있고, 부담금이 아니면 반드시 없다 (양방향 등가)
    CONSTRAINT chk_pension_tx_source CHECK (
        (tx_type = 'CONTRIBUTION') = (contribution_source IS NOT NULL)
        AND (contribution_source IS NULL OR contribution_source IN ('EMPLOYER', 'EMPLOYEE'))),
    -- 중도인출이면 법정 사유가 반드시 있고, 중도인출이 아니면 반드시 없다
    CONSTRAINT chk_pension_tx_reason CHECK (
        (tx_type = 'MID_WITHDRAWAL') = (mid_withdrawal_reason IS NOT NULL)
        AND (mid_withdrawal_reason IS NULL OR mid_withdrawal_reason IN (
            'HOMELESS_HOUSE_PURCHASE', 'LONG_TERM_CARE_6_MONTHS', 'BANKRUPTCY',
            'PERSONAL_REHABILITATION', 'NATURAL_DISASTER', 'MINISTER_NOTICE'))),
    -- ★ GL 전표 자연키의 유일성 근거 — ref_id = 'RP-{pension_id}-{seq}'
    CONSTRAINT uq_pension_transaction_seq UNIQUE (pension_id, seq)
);

-- 계약 거래이력 조회 (발생 순서 = seq 오름차순)
CREATE INDEX IF NOT EXISTS idx_pension_transactions_pension
    ON pension_transactions (pension_id, seq);

COMMENT ON TABLE retirement_pensions IS
    '퇴직연금 계약 서브원장 — 계정계가 정본을 소유한다(소비 전용 경계의 예외: banking 수신 상품). GL 전기는 인프로세스.';
COMMENT ON TABLE pension_transactions IS
    '퇴직연금 거래 이력 — append-only. 갱신·삭제 없음. (pension_id, seq) 가 GL 전표 자연키의 유일성 근거.';

COMMENT ON COLUMN retirement_pensions.subscriber_id IS
    'GL owner_id 와 동일 규약 — OwnerType.DEPOSITOR 의 ownerId = userId 숫자 문자열. JWT 주체에서만 파생(IDOR 차단).';
COMMENT ON COLUMN retirement_pensions.scheme IS
    'DB=확정급여(사업장 필수·사용자 부담금만·중도인출 불가), DC=확정기여(사업장 필수·양측 부담금·중도인출 가능), IRP=개인형(사업장 없음·본인 납입만·중도인출 가능).';
COMMENT ON COLUMN retirement_pensions.birth_date IS
    '수급 개시 연령(만 55세) 판정의 근거. 수급 신청 때 받으면 아무나 나이를 자유 입력해 요건 검사가 무력화되므로 가입 시 1회만 기록한다. DB-per-service 라 사용자 서비스 프로필과 대조할 수 없어 자기신고 값이며, 검증된 신원 소스가 붙으면 대체해야 한다.';
COMMENT ON COLUMN retirement_pensions.last_interest_settled_on IS
    '직전 이자 확정일 = 다음 이자의 기산일. 이자 금액은 클라이언트가 보내지 않고 서버가 (적립금 x annual_rate x 경과일수 / 365) 로 산출하므로, 이 컬럼이 곧 산출식의 시작점이다. 산출 이자가 0원이면 옮기지 않는다(경과일수 소멸 방지).';
COMMENT ON COLUMN retirement_pensions.next_seq IS
    '다음 거래의 seq. 계약 단위 단조 증가 — 전역 시퀀스를 쓰면 계약별 단조성이 깨져 전표 자연키 규약이 무너진다.';
COMMENT ON COLUMN pension_transactions.seq IS
    'GL 전표 ref_id ''RP-{pension_id}-{seq}'' 의 마지막 조각. 이 값이 중복되면 두 번째 거래의 전표가 조용히 유실된다.';
COMMENT ON COLUMN pension_transactions.mid_withdrawal_reason IS
    '근로자퇴직급여보장법 시행령 법정 인출사유 6종 — 무주택 주택구입/6개월 이상 요양/파산선고/개인회생/천재지변/고용노동부장관 고시.';

COMMENT ON CONSTRAINT chk_pension_employer_by_scheme ON retirement_pensions IS
    '사업장 필수(DB·DC)/금지(IRP) — 도메인 PensionScheme.requiresEmployerName() 과 같은 규칙을 스키마에서도 닫는다.';
COMMENT ON CONSTRAINT chk_pension_birth_date ON retirement_pensions IS
    '가입 시점 만 15세 이상 + 생년월일 <= 가입일 — 도메인 RetirementPension.open() 의 검사를 스키마에서도 닫는다. 이 값이 오염되면 만 55세 수급요건이 통째로 무너진다.';
COMMENT ON CONSTRAINT uq_pension_transaction_seq ON pension_transactions IS
    '계약당 거래 seq 유일 — GL 전표 자연키(source_topic, ref_type, ref_id) 유일성의 서브원장 측 근거.';
