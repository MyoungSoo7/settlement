package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 판매자 인바운드 포트 (유스케이스)
 */
public interface SellerUseCase {

    Seller register(RegisterSellerCommand cmd);

    Seller approve(Long sellerId);

    Seller reject(Long sellerId);

    Seller suspend(Long sellerId);

    Seller reactivate(Long sellerId);

    Seller updateBankInfo(Long sellerId, UpdateBankInfoCommand cmd);

    Seller updateCommissionRate(Long sellerId, BigDecimal rate);

    Seller getSeller(Long sellerId);

    Seller getSellerByUserId(Long userId);

    List<Seller> getAllSellers();

    List<Seller> getSellersByStatus(SellerStatus status);

    record RegisterSellerCommand(
            Long userId,
            String businessName,
            String businessNumber,
            String representativeName,
            String phone,
            String email
    ) {}

    record UpdateBankInfoCommand(
            String bankName,
            String accountNumber,
            String accountHolder
    ) {}
}
