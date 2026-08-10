package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.InsuranceApplication;

/**
 * 청약 저장 포트.
 *
 * <p>신규 저장 시 PII(피보험자 RRN·계약자 연락처)는 청약 본문과 분리된 전용 테이블에
 * AES 암호화로 저장된다 — 도메인은 평문을 들고 다니지 않고, 접수 시점에만 통과시킨다.
 */
public interface SaveApplicationPort {

    /** 신규 접수 저장 — 청약 본문 + (제공된 경우) PII 분리 저장. */
    InsuranceApplication saveNew(InsuranceApplication application, ApplicationPii pii);

    /** 상태 전이 반영 — 기존 행 갱신. */
    InsuranceApplication update(InsuranceApplication application);

    /**
     * @param insuredRrn      피보험자 주민등록번호 (nullable)
     * @param contractorPhone 계약자 연락처 (nullable)
     */
    record ApplicationPii(String insuredRrn, String contractorPhone) {

        public static final ApplicationPii NONE = new ApplicationPii(null, null);
    }
}
