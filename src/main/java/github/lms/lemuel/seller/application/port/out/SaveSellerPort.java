package github.lms.lemuel.seller.application.port.out;

import github.lms.lemuel.seller.domain.Seller;

/**
 * 판매자 저장 아웃바운드 포트
 */
public interface SaveSellerPort {

    Seller save(Seller seller);
}
