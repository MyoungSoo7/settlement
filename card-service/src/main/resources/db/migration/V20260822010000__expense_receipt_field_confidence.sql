-- 영수증 판독 신뢰도를 필드별로 분리한다 (ADR 0036 후속).
--
-- 왜: 신뢰도가 추출 전체에 대한 값 하나였던 탓에, 쉬운 필드(총액)의 확신이 어려운 필드(거래일)의
-- 불확실성을 덮었다. 실측에서 비전 모델이 반사광에 덮인 거래일을 6년 틀리게 읽고도 총액을
-- 또렷하게 읽었다는 이유로 0.98 을 붙였고, 그 값이 임계(0.80)를 넘어 멀쩡한 영수증이
-- MISMATCHED 로 종결됐다. MISMATCHED 는 종결 상태라 재첨부 외엔 되돌릴 방법이 없다.
--
-- 기존 행 이관: 거래일이 있는 행은 그때 알던 유일한 숫자를 양쪽에 그대로 물려준다. 없던 정보를
-- 새로 지어내지 않으며, 이관으로 판정이 바뀌지도 않는다(같은 값이면 기존과 같은 게이트 결과).

ALTER TABLE expense_receipts RENAME COLUMN confidence TO amount_confidence;

ALTER TABLE expense_receipts ADD COLUMN date_confidence NUMERIC(3,2);

UPDATE expense_receipts
   SET date_confidence = amount_confidence
 WHERE transaction_date IS NOT NULL;

-- 컬럼 이름이 바뀌었으므로 CHECK 제약도 이름을 맞춘다(제약 식 자체는 rename 을 따라간다).
ALTER TABLE expense_receipts
    RENAME CONSTRAINT chk_receipt_confidence_range TO chk_receipt_amount_confidence_range;

ALTER TABLE expense_receipts
    ADD CONSTRAINT chk_receipt_date_confidence_range
        CHECK (date_confidence IS NULL OR (date_confidence >= 0 AND date_confidence <= 1));

-- 도메인 불변식을 DB 에서도 잠근다: 거래일이 없으면 거래일 신뢰도도 없고, 있으면 반드시 있다.
-- 한쪽만 있는 행은 판정이 흔들리는 상태라 애초에 존재해서는 안 된다.
ALTER TABLE expense_receipts
    ADD CONSTRAINT chk_receipt_date_confidence_paired
        CHECK ((transaction_date IS NULL) = (date_confidence IS NULL));

COMMENT ON COLUMN expense_receipts.amount_confidence IS '총액 판독 신뢰도 0~1 (필수)';
COMMENT ON COLUMN expense_receipts.date_confidence IS '거래일 판독 신뢰도 0~1 (거래일이 NULL 이면 NULL)';
