package github.lms.lemuel.giftcard.application.port.out;

import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;

import java.util.List;

/**
 * 기프트카드 원장 엔트리 저장 포트 (append-only).
 *
 * <p>{@link #loadByReference} 는 <b>카드를 가로질러</b> 같은 참조의 엔트리를 모은다 —
 * 결제 1건이 여러 장을 걸쳤을 때 환불이 그 전부를 되짚어야 하기 때문이다.
 */
public interface GiftCardEntryPort {

    GiftCardEntry append(GiftCardEntry entry);

    int nextSequence(Long giftCardId, GiftCardEntryType type, String referenceType, String referenceId);

    boolean exists(Long giftCardId, GiftCardEntryType type, String referenceType,
                   String referenceId, int sequence);

    /** 같은 참조의 엔트리 전부(카드 무관). 환불 복원이 "무엇을 얼마나 썼는지" 아는 유일한 근거. */
    List<GiftCardEntry> loadByReference(GiftCardEntryType type, String referenceType, String referenceId);
}
