package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 기프트카드 소비 순서 결정 — 어떤 장부터 쓸지를 정하는 유일한 지점.
 *
 * <p><b>만료 임박 순</b>으로 쓴다. 상품권은 만료가 곧 고객 손실이라 오래된 것부터 소진하는 편이
 * 고객에게 유리하다. 만료일이 같으면 먼저 발급된(id 가 작은) 장이 앞선다.
 *
 * <p><b>부족하면 아무것도 건드리지 않는다.</b> 계획을 먼저 세워 총액을 확인한 뒤에 적용하므로,
 * 잔액 부족으로 거절될 때 일부 카드만 깎여 있는 중간 상태가 생기지 않는다.
 */
public final class GiftCardSelector {

    private static final Comparator<GiftCard> CONSUME_ORDER =
            Comparator.comparing(GiftCard::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(GiftCard::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private GiftCardSelector() {
    }

    /**
     * {@code requested} 만큼을 소비 순서대로 카드에서 차감하고, 어느 장을 얼마나 썼는지 반환한다.
     * 전달된 카드들은 이 호출로 <b>변경된다</b> — 계획과 적용을 분리하면 적용을 잊는 경로가 생긴다.
     *
     * @throws InsufficientGiftCardBalanceException 쓸 수 있는 카드의 잔액 합이 요청액에 못 미칠 때
     */
    public static List<GiftCardCharge> consume(List<GiftCard> cards, BigDecimal requested) {
        BigDecimal target = GiftCardAmounts.require(requested, "consume");

        List<GiftCard> candidates = cards.stream()
                .filter(card -> card.getStatus().isSpendable() && card.getRemainingAmount().signum() > 0)
                .sorted(CONSUME_ORDER)
                .toList();

        BigDecimal available = candidates.stream()
                .map(GiftCard::getRemainingAmount)
                .reduce(GiftCardAmounts.zero(), BigDecimal::add);
        if (available.compareTo(target) < 0) {
            throw new InsufficientGiftCardBalanceException(
                    "기프트카드 잔액 부족: 요청 " + target + ", 가용 " + available, target, available);
        }

        // 1단계 — 계획만 세운다(카드 미변경).
        List<GiftCardCharge> plan = new ArrayList<>();
        BigDecimal remaining = target;
        for (GiftCard card : candidates) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal take = card.getRemainingAmount().min(remaining);
            plan.add(new GiftCardCharge(card.getId(), take));
            remaining = remaining.subtract(take);
        }

        // 2단계 — 총액이 확인된 뒤에만 적용한다.
        int index = 0;
        for (GiftCardCharge charge : plan) {
            candidates.get(index++).use(charge.amount());
        }
        return List.copyOf(plan);
    }

    /** 쓸 수 있는 카드의 잔액 합 — 사용자에게 보여 줄 "상품권 잔액"이다. */
    public static BigDecimal spendableBalance(List<GiftCard> cards) {
        return cards.stream()
                .filter(card -> card.getStatus().isSpendable())
                .map(GiftCard::getRemainingAmount)
                .reduce(GiftCardAmounts.zero(), BigDecimal::add);
    }
}
