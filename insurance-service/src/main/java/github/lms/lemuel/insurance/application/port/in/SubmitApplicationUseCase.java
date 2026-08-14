package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.SalesChannel;

import java.math.BigDecimal;

/**
 * 청약 접수 유스케이스.
 *
 * <p>피보험자 RRN·계약자 연락처는 별도 PII 테이블에 AES 암호화 저장된다(청약 본문과 분리).
 */
public interface SubmitApplicationUseCase {

    /** @return 채번된 applicationId (UUID) */
    String submit(SubmitApplicationCommand command);

    /**
     * @param consultationId  상담 경유 유입 시 (선택)
     * @param insuredRrn      피보험자 주민등록번호 (선택 — 제공 시 암호화 저장)
     * @param contractorPhone 계약자 연락처 (선택 — 제공 시 암호화 저장)
     * @param partnerBankCode BANCA 청약 시 필수 (도메인이 강제)
     */
    record SubmitApplicationCommand(String consultationId, String productCode, String fcId,
                                    String insuredName, String contractorName,
                                    String insuredRrn, String contractorPhone,
                                    BigDecimal desiredCoverage, BigDecimal desiredPremium,
                                    SalesChannel salesChannel, String partnerBankCode) {
    }
}
