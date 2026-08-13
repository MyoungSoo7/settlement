package github.lms.lemuel.deposit.adapter.in.web.dto;

import github.lms.lemuel.deposit.domain.SellerDepositAccount;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 셀러 예치 계좌 잔고 응답.
 *
 * <p>{@code available}(즉시 쓸 수 있는 금액)과 {@code locked}(카드 승인 등으로 선점된 금액)를
 * 나눠서 내려 준다 — {@code total} 하나만 보이면 "잔고는 있는데 왜 결제가 막히나"를 설명할 수 없다.
 * 도메인 불변식은 {@code total = available + locked} 다.
 */
public record DepositAccountResponse(
        Long id,
        Long sellerId,
        BigDecimal available,
        BigDecimal locked,
        BigDecimal total,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static DepositAccountResponse from(SellerDepositAccount account) {
        return new DepositAccountResponse(
                account.getId(),
                account.getSellerId(),
                account.getAvailable(),
                account.getLocked(),
                account.getTotal(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
