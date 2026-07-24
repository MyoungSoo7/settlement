package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.application.TaxPostingResult;

/**
 * 정산 확정 시점의 세무 전표 전기 유스케이스 — 부가세·원천징수 예수 분개를 settlement 자체원장에 전기.
 */
public interface PostSettlementTaxUseCase {

    /**
     * 지정 정산의 세무 전표를 전기한다(멱등). 미등록 셀러·미확정 정산은 보류 결과로 반환한다.
     *
     * @param settlementId 대상 정산 식별자
     * @param sellerId     세무유형 판단을 위한 셀러 식별자
     */
    TaxPostingResult postForSettlement(Long settlementId, Long sellerId);
}
