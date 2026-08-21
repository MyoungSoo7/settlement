-- 세금계산서 스캔 판독 신뢰도를 판정 축별로 분리한다.
--
-- 왜: 신뢰도가 추출 전체에 대한 값 하나였다. 금액 3종은 서로 산술로 교차검증되지만(합계 =
-- 공급가액 + 세액, 세액 = 공급가액 × 10%) 승인번호는 교차검증할 상대가 없다. 그런데도 신뢰도가
-- 하나라 금액을 또렷하게 읽었다는 사실이 뭉개진 승인번호의 불확실성을 덮었다. 승인번호는 대사의
-- 탐색 키라, 이게 잘못 읽히면 왕복 검증이 실패해 "발행분을 못 찾았다"(UNMATCHED)는 틀린 결론이
-- 기록된다.
--
-- 기존 행 이관: 그때 알던 유일한 숫자를 두 축에 그대로 물려준다. 없던 정보를 지어내지 않으며,
-- 같은 값이면 기존과 같은 리뷰 판정이 나온다.

ALTER TABLE public.tax_invoice_scans RENAME COLUMN confidence TO amount_confidence;

ALTER TABLE public.tax_invoice_scans
    ADD COLUMN approval_number_confidence numeric(4,3);

UPDATE public.tax_invoice_scans
   SET approval_number_confidence = amount_confidence
 WHERE approval_number_confidence IS NULL;

ALTER TABLE public.tax_invoice_scans
    ALTER COLUMN approval_number_confidence SET NOT NULL;

-- 컬럼 이름이 바뀌었으니 CHECK 제약 이름도 맞춘다(제약 식 자체는 rename 을 따라간다).
ALTER TABLE public.tax_invoice_scans
    RENAME CONSTRAINT chk_tax_invoice_scan_confidence TO chk_tax_invoice_scan_amount_confidence;

ALTER TABLE public.tax_invoice_scans
    ADD CONSTRAINT chk_tax_invoice_scan_approval_confidence
        CHECK (approval_number_confidence >= 0 AND approval_number_confidence <= 1);

COMMENT ON COLUMN public.tax_invoice_scans.amount_confidence IS
    '금액 3종 판독 신뢰도 0~1. 결정적 텍스트 파서는 1.000, LLM 은 모델 보고값.';
COMMENT ON COLUMN public.tax_invoice_scans.approval_number_confidence IS
    '승인번호 판독 신뢰도 0~1. 대사 탐색 키라 금액과 따로 본다(교차검증할 상대가 없다).';
