package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.domain.SellerTaxProfile;

public interface SaveSellerTaxProfilePort {

    SellerTaxProfile save(SellerTaxProfile profile);
}
