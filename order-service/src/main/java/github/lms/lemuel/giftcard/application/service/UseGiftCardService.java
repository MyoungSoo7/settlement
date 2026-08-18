package github.lms.lemuel.giftcard.application.service;

import github.lms.lemuel.giftcard.application.port.in.UseGiftCardUseCase;
import github.lms.lemuel.giftcard.application.port.out.GiftCardEntryPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.application.port.out.PublishGiftCardEventPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardCharge;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import github.lms.lemuel.giftcard.domain.GiftCardSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기프트카드 사용(차감) — 결제의 GIFT_CARD 텐더가 부르는 경로.
 *
 * <p>포인트와 순서가 같다: 멱등 단축 반환 → 카드 락 → 만료 임박 순 소비 계획 → 총액 확인 후 적용.
 * 다른 점은 잠글 대상이 계정이 아니라 <b>카드들</b>이라는 것뿐이다.
 *
 * <p>한 결제가 여러 장을 걸치면 <b>카드마다 원장 엔트리가 하나씩</b> 생긴다. 같은 tender 를 참조로
 * 공유하므로, 환불은 그 참조로 되짚어 어느 장에 얼마를 돌려줄지 알아낸다.
 */
@Service
@Transactional
public class UseGiftCardService implements UseGiftCardUseCase {

    private static final Logger log = LoggerFactory.getLogger(UseGiftCardService.class);

    private final GiftCardPort giftCardPort;
    private final GiftCardEntryPort entryPort;
    private final PublishGiftCardEventPort eventPort;

    public UseGiftCardService(GiftCardPort giftCardPort, GiftCardEntryPort entryPort,
                              PublishGiftCardEventPort eventPort) {
        this.giftCardPort = giftCardPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public UseGiftCardResult use(UseGiftCardCommand command) {
        List<GiftCard> cards = giftCardPort.loadSpendable(command.userId());

        // 같은 tender 를 두 번 차감하지 않는다. 카드가 여러 장이라 어느 한 장이라도 기록돼 있으면
        // 이미 처리된 요청이다.
        if (alreadyCharged(cards, command)) {
            log.info("기프트카드 사용 멱등 단축 반환: userId={}, ref={}:{}",
                    command.userId(), command.referenceType(), command.referenceId());
            return new UseGiftCardResult(command.amount(), 0,
                    GiftCardSelector.spendableBalance(cards));
        }

        List<GiftCardCharge> charges = GiftCardSelector.consume(cards, command.amount());
        Map<Long, GiftCard> byId = new HashMap<>();
        for (GiftCard card : cards) {
            byId.put(card.getId(), card);
        }

        giftCardPort.saveAll(cards);
        for (GiftCardCharge charge : charges) {
            GiftCard card = byId.get(charge.giftCardId());
            int sequence = entryPort.nextSequence(card.getId(), GiftCardEntryType.USE,
                    command.referenceType(), command.referenceId());
            GiftCardEntry entry = entryPort.append(GiftCardEntry.use(card.getId(), charge.amount(),
                    command.referenceType(), command.referenceId(), sequence, command.actor()));
            eventPort.giftCardUsed(card, entry);
        }

        BigDecimal remaining = GiftCardSelector.spendableBalance(cards);
        log.info("기프트카드 사용: userId={}, amount={}, cards={}, 잔액={}",
                command.userId(), command.amount(), charges.size(), remaining);
        return new UseGiftCardResult(command.amount(), charges.size(), remaining);
    }

    private boolean alreadyCharged(List<GiftCard> cards, UseGiftCardCommand command) {
        return cards.stream().anyMatch(card -> entryPort.exists(card.getId(), GiftCardEntryType.USE,
                command.referenceType(), command.referenceId(), 0));
    }

    /** 잔액 조회 — 결제 화면이 "상품권으로 얼마까지 낼 수 있나"를 물을 때. */
    @Transactional(readOnly = true)
    public BigDecimal spendableBalance(Long userId) {
        return GiftCardSelector.spendableBalance(giftCardPort.loadSpendableReadOnly(userId));
    }
}
