package github.lms.lemuel.tax.domain.exception;

/**
 * 세무 프로필 미등록 — 셀러의 세무유형(개인/사업자)이 레지스트리에 없어 세무 산출을 보류한다.
 *
 * <p>ADR 0027: 미등록 셀러는 임의 기본값으로 산출을 강행하지 않고 보류한다(관리자 등록 유도).
 * 관리자 트리거 경로에서 이 예외로 "보류" 사유를 드러내며, 배치 경로는 결과값으로 흡수한다.
 */
public class SellerTaxProfileNotRegisteredException extends TaxDomainException {

    public SellerTaxProfileNotRegisteredException(Long sellerId) {
        super("셀러 세무 프로필 미등록 — 세무 산출 보류 (sellerId=" + sellerId + ")");
    }
}
