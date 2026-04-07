package github.lms.lemuel.seller.adapter.in.web.dto;

import github.lms.lemuel.seller.domain.Seller;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SellerResponse(
        Long id,
        Long userId,
        String businessName,
        String businessNumber,
        String representativeName,
        String phone,
        String email,
        String bankName,
        String bankAccountNumber,
        String bankAccountHolder,
        BigDecimal commissionRate,
        String status,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SellerResponse from(Seller seller) {
        return new SellerResponse(
                seller.getId(),
                seller.getUserId(),
                seller.getBusinessName(),
                seller.getBusinessNumber(),
                seller.getRepresentativeName(),
                seller.getPhone(),
                seller.getEmail(),
                seller.getBankName(),
                seller.getBankAccountNumber(),
                seller.getBankAccountHolder(),
                seller.getCommissionRate(),
                seller.getStatus().name(),
                seller.getApprovedAt(),
                seller.getCreatedAt(),
                seller.getUpdatedAt()
        );
    }
}
