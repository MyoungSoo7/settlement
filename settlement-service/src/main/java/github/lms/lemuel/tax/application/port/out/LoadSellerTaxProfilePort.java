package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.domain.SellerTaxProfile;

import java.util.Optional;

public interface LoadSellerTaxProfilePort {

    Optional<SellerTaxProfile> findBySellerId(Long sellerId);
}
