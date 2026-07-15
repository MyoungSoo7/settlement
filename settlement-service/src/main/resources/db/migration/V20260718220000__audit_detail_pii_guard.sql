-- V20260718220000: audit_logs.detail_json 주민등록번호 패턴 유입 거부 트리거 (R5 리뷰 후속, settlement)
--
-- order V20260718130000 과 동형 — 형식이 확정적인 주민등록번호 패턴만 DB 레벨 fail-loud 거부,
-- 계좌·전화 등 느슨한 형식은 오탐(금액·ID 충돌) 위험으로 기록기 마스킹 계약에 위임.
-- BEFORE INSERT row 트리거는 파티션드 부모에 걸면 전 파티션에 적용.

CREATE OR REPLACE FUNCTION audit_detail_reject_rrn()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.detail_json IS NOT NULL
       AND NEW.detail_json::text ~ '\d{6}-[1-4]\d{6}' THEN
        RAISE EXCEPTION 'audit_logs.detail_json 에 주민등록번호 패턴 유입 — 기록기 마스킹 계약 위반(유입 전 마스킹 필수)';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_detail_rrn_guard ON public.audit_logs;
CREATE TRIGGER trg_audit_detail_rrn_guard
    BEFORE INSERT ON public.audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_detail_reject_rrn();
