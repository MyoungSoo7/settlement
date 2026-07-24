package github.lms.lemuel.account.application.port.out;

import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.OwnerType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * GL 분개 조회 아웃바운드 포트.
 *
 * <p>집계용 합계/건수는 DB SUM/COUNT 로 계산해 반환한다(전표 전량 로드 회피). 합계가 없으면 0 을
 * 반환하며 null 을 노출하지 않는다 — JPQL {@code :param IS NULL OR} 트랩(PG bytea 오류)을 피하기 위해
 * refType 별 전용 집계 메서드로 분기한다.
 */
public interface LoadAccountEntryPort {

    /** owner 의 전표 전량(요약 계산용). */
    List<AccountEntry> findByOwner(OwnerType ownerType, String ownerId);

    /** owner 의 전표 페이지(occurredAt/id 최신순). */
    List<AccountEntry> findByOwnerPaged(OwnerType ownerType, String ownerId, int page, int size);

    /** owner 의 전표 총 건수. */
    long countByOwner(OwnerType ownerType, String ownerId);

    /** refType 별 금액 합계(없으면 0). */
    BigDecimal sumAmountByRefType(String refType);

    /** refType 별 전표 건수. */
    long countByRefType(String refType);

    /**
     * 셀러의 현재 SELLER_PAYABLE 순잔액(credit합 − debit합, 없으면 0). 음수 방지 payout 분할용.
     *
     * <p>DB 집계(SUM)로 계산해 전표 전량 로드를 피하며, COALESCE 로 매칭 행이 없어도 null 없이 0 을 반환한다
     * ({@code :param IS NULL OR} 트랩 회피 — owner·계정을 고정 파라미터로 바인딩).
     */
    BigDecimal sellerPayableBalance(String sellerId);

    /** 전체 전표(시산표 계산용). */
    List<AccountEntry> findAll();

    /** occurred_at 기간 전표(기간 확정 시산표 계산용). from 이상 ~ to 미만(반개구간). */
    List<AccountEntry> findByOccurredAtBetween(LocalDateTime fromInclusive, LocalDateTime toExclusive);
}
