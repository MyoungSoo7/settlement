package github.lms.lemuel.seller.application.port.out;

import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;

import java.util.List;
import java.util.Optional;

/**
 * 판매자 조회 아웃바운드 포트
 */
public interface LoadSellerPort {

    Optional<Seller> findById(Long sellerId);

    Optional<Seller> findByUserId(Long userId);

    Optional<Seller> findByBusinessNumber(String businessNumber);

    List<Seller> findAll();

    List<Seller> findByStatus(SellerStatus status);
}
