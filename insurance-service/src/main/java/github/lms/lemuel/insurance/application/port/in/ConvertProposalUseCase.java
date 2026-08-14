package github.lms.lemuel.insurance.application.port.in;

import java.math.BigDecimal;

/**
 * 가입설계 → 청약 전환 유스케이스.
 *
 * <p><b>D-P3</b>: 보험료·보장금액은 클라이언트에서 받지 않는다 — 설계 스냅샷의 산출값을
 * 서버가 청약에 주입한다(금액 위변조 차단). 전환은 설계 작성자 본인만 할 수 있다(소유권 대조).
 */
public interface ConvertProposalUseCase {

    ConversionResult convert(ConvertProposalCommand cmd);

    /**
     * @param requesterFcId  전환 요청자 — 설계의 fcId 와 불일치 시 403
     * @param insuredRrn     선택 — 제공 시 PII 분리 테이블에 암호화 저장
     * @param contractorPhone 선택 — 제공 시 PII 분리 테이블에 암호화 저장
     */
    record ConvertProposalCommand(String proposalId, String requesterFcId, String contractorName,
                                  String insuredRrn, String contractorPhone) {
    }

    record ConversionResult(String proposalId, String applicationId, BigDecimal annualPremium) {
    }
}
