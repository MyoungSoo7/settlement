package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;

/**
 * 셀러 세무 프로필 등록·정정(upsert) 유스케이스 — 관리자(ADMIN/MANAGER) 콘솔에서 호출.
 */
public interface RegisterSellerTaxProfileUseCase {

    SellerTaxProfile register(Long sellerId, TaxType taxType, String businessRegNo);
}
