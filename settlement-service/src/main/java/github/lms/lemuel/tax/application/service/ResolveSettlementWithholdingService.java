package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.WithholdingResolution;
import github.lms.lemuel.tax.application.port.in.ResolveSettlementWithholdingUseCase;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxCalculation;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 원천징수 해석 서비스 — 세무 프로필 레지스트리만 조회하는 가벼운 서비스(commission 불필요,
 * {@link TaxCalculation#computeWithholding} 재사용으로 요율·절사 정책 단일 출처 유지).
 *
 * <p>미등록 셀러 정책은 {@link WithholdingResolution} 문서 참조 — 사업자 취급(원천징수 0)으로 폴백한다.
 */
@Service
@Transactional(readOnly = true)
public class ResolveSettlementWithholdingService implements ResolveSettlementWithholdingUseCase {

    private final LoadSellerTaxProfilePort loadProfilePort;

    public ResolveSettlementWithholdingService(LoadSellerTaxProfilePort loadProfilePort) {
        this.loadProfilePort = loadProfilePort;
    }

    @Override
    public WithholdingResolution resolveForPayout(Long sellerId, BigDecimal netAmount) {
        if (sellerId == null) {
            throw new TaxInvariantViolationException("sellerId 는 필수입니다");
        }
        return loadProfilePort.findBySellerId(sellerId)
                .map(profile -> resolved(profile, netAmount))
                .orElseGet(WithholdingResolution::unregistered);
    }

    private WithholdingResolution resolved(SellerTaxProfile profile, BigDecimal netAmount) {
        BigDecimal withholding = TaxCalculation.computeWithholding(netAmount, profile.getTaxType());
        return WithholdingResolution.of(profile.getTaxType(), withholding);
    }
}
