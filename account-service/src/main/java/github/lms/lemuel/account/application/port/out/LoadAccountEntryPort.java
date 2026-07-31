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
     * <p>DB 집계(SUM)로 계산해 전표 전량을 애플리케이션에 로드하지 않으며, COALESCE 로 매칭 행이 없어도
     * null 없이 0 을 반환한다({@code :param IS NULL OR} 트랩 회피 — owner·계정을 고정 파라미터로 바인딩).
     *
     * <p><b>비용(정직한 한정 — 코드리뷰 #6)</b>: {@code idx_account_entries_owner (owner_type, owner_id, id)}
     * 로 셀러 행 범위만 스캔하지만(풀 테이블 스캔 아님), payout 마다 그 셀러의 <b>누적 전표 전량을 재합산</b>하므로
     * 여전히 O(셀러 전표 수)이고, advisory 락을 쥔 채 실행돼 이력이 길수록 락 보유 시간이 늘어난다. O(1) 로 낮추려면
     * (seller, account)별 <b>실체화 running balance</b> 를 매 전표 insert 시 갱신해야 한다(별도 큰 변경 —
     * 현재는 인덱스 기반 스캔을 수용).
     */
    BigDecimal sellerPayableBalance(String sellerId);

    /** 전체 전표(시산표 계산용). */
    List<AccountEntry> findAll();

    /** occurred_at 기간 전표(기간 확정 시산표 계산용). from 이상 ~ to 미만(반개구간). */
    List<AccountEntry> findByOccurredAtBetween(LocalDateTime fromInclusive, LocalDateTime toExclusive);

    /**
     * 해당 대출에 <b>원금 건별 전기</b>가 한 건이라도 있는지 (#183 롤아웃 호환 판정).
     *
     * <p>refId 규약이 {@code loanId#eventId} 라 접두 일치로 본다. loan/account 를 독립 롤아웃할 때
     * 어느 쪽이 먼저 뜨든 채권이 어긋나지 않게 하는 유일한 판단 근거다 — 자세한 사정은
     * {@code SecuredLoanRepaidConsumer} 주석 참조.
     */
    boolean hasPrincipalRepaidEntry(String loanId);
}
