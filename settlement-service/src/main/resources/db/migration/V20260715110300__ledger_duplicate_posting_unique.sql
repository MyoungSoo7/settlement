-- V20260715110300: 원장 중복 분개 방지 UNIQUE — DB 설계 리뷰 후속 (레인 E1)
--
-- 무엇을: (reference_id, reference_type, debit_account, credit_account) 유니크 인덱스.
-- 왜: CreateLedgerEntry 경로는 existsByReference 소프트 체크로 멱등을 보장하지만, 같은 settlementId
--     확정이 동시에 두 번 실행되면 양쪽 모두 exists=false 를 보고 각각 INSERT 해 동일 분개가 2배로
--     적재될 수 있다(TOCTOU 경합) → 복식부기 대차가 깨진다. order-service 는 V20260606120000 으로 이미
--     이 유니크를 갖지만 settlement_db(V1 export)엔 없었다 — order 동형으로 복원한다.
--
-- 한 정산은 (reference_id, reference_type)가 같은 서로 다른 계정쌍 row 들로 분해되므로(예:
--   ACCOUNTS_PAYABLE/REVENUE + COMMISSION_EXPENSE/COMMISSION_REVENUE) reference 만으로는 유니크할 수
--   없다. 계정쌍까지 포함해 "정확히 같은 분개"의 이중 적재만 차단한다.
--
-- 안전성: UNIQUE INDEX 는 NOT VALID 를 지원하지 않아(제약이 아닌 인덱스) 생성 시 즉시 전수 검사한다.
--   빈 DB 는 즉시 통과. 기존 DB 에 중복이 있다면 그 자체가 멱등 결함이므로 생성 실패로 조기 노출된다.
--   (Flyway 는 트랜잭션 안에서 실행하므로 CONCURRENTLY 는 사용 불가 — 일반 CREATE UNIQUE INDEX.)
CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_reference_accounts
    ON public.ledger_entries (reference_id, reference_type, debit_account, credit_account);

COMMENT ON INDEX public.uq_ledger_reference_accounts IS
    '중복 분개 방지 — 같은 (거래, 계정쌍) 조합의 원장 항목은 단 1건만 허용. 동시 정산 확정 이중 적재 차단.';
