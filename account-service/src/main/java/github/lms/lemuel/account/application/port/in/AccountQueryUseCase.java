package github.lms.lemuel.account.application.port.in;

/**
 * 계정계 조회 인바운드 포트 — owner 잔액·분개 페이지·대출/투자/정산 집계·시산표.
 *
 * <p><b>ISP</b>: 조회 축을 응집 역할 인터페이스로 분리했다 — {@link OwnerAccountQuery}(owner 화면),
 * {@link AccountAggregateQuery}(전사 집계), {@link TrialBalanceQuery}(시산표). 이 포트는 셋을 합성한
 * 편의 집합이며, 한 축만 필요한 소비처는 해당 역할 인터페이스만 의존하면 된다. 구현 서비스는 이 합성
 * 포트를 구현해 세 역할을 한 번에 만족시킨다. (중첩 레코드 {@code EntryPage}/{@code LoanAggregate}
 * 등은 각 역할 인터페이스가 정본으로 선언한다 — {@code OwnerAccountQuery.EntryPage},
 * {@code AccountAggregateQuery.LoanAggregate} 로 참조한다.)
 */
public interface AccountQueryUseCase
        extends OwnerAccountQuery,
                AccountAggregateQuery,
                TrialBalanceQuery {
}
