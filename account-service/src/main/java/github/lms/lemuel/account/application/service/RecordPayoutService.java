package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.application.port.in.RecordPayoutUseCase;
import github.lms.lemuel.account.application.port.out.AcquireSellerLockPort;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 셀러 실지급(payout.completed) 을 현재 SELLER_PAYABLE 잔액 기준으로 분할 전기한다 (감사 MED-3 봉합).
 *
 * <p>대응하는 SELLER_PAYABLE 크레딧이 없는 실지급(예: 수동 송금)이 통제계정을 음수로 모는 것을 막기 위해
 * payout 차변을 두 채널로 라우팅한다.
 * <ul>
 *   <li>{@code payable = min(amount, max(0, 현재 SELLER_PAYABLE 잔액))} → {@link AccountEntry#payoutCompleted}
 *       (DR SELLER_PAYABLE / CR CASH) — 미지급금 상계</li>
 *   <li>{@code advance = amount − payable} → {@link AccountEntry#payoutAdvanceReceivable}
 *       (DR SELLER_RECOVERY_RECEIVABLE / CR CASH) — 초과 실지급을 회수채권으로 인식</li>
 * </ul>
 * 두 전표 합 = amount 라 CASH 유출 총액은 정확히 기록된다. 각 전기는
 * {@code RecordAccountEntryUseCase.record} 의 자연키 멱등 경로를 타고, 두 전표는 refType 으로 자연키가
 * 갈려(PAYOUT_COMPLETED·PAYOUT_ADVANCE, refId=payoutId 동일) 각각 멱등이다.
 *
 * <p><b>보장 범위(정직한 한정 — 코드리뷰 #5)</b>: 이 분할은 <b>payout 자신이 현재 잔액을 초과 상계해</b>
 * SELLER_PAYABLE 을 음수로 모는 것만 막는다. "SELLER_PAYABLE 은 절대 음수가 아니다"를 전역으로 보장하지는
 * 않는다 — withholding_accrued·settlement_canceled·seller_recovery.offset 등 <b>무조건(잔액 비의존) DR
 * SELLER_PAYABLE</b> 전기는 잔액을 읽지 않으므로, payout 이 잔액을 소진한 뒤(또는 동시에) 도착하면 통제계정을
 * 음수로 만들 수 있다. 이때 음수는 회계적으로 "셀러가 플랫폼에 빚짐(과다 원천징수/사후 조정)"을 뜻하며, 이를
 * 전역 불변식으로 강제하려면 모든 SELLER_PAYABLE debit 에 잔액 인식 라우팅을 적용하거나 실체화 잔액을 도입해야
 * 한다(별도 큰 변경 — 현재는 이 한정을 수용).
 *
 * <p><b>동시성</b>: 잔액 읽기 전에 셀러 단위 PG advisory xact 락({@link AcquireSellerLockPort})을 잡아 같은
 * 셀러의 <b>동시 payout</b>을 직렬화한다(payout-vs-payout 만 — 위 무조건 debit 은 이 락을 잡지 않는다).
 * 락이 없으면 둘째 payout 이 첫째의 미반영 잔액을 읽어 SELLER_PAYABLE 을 음수로 몰고 초과분 회수채권을 놓친다
 * (payoutId 파티셔닝 + concurrency>1 에서 실재현). xact 락이라 이 {@code @Transactional}(컨슈머 tx 조인)
 * 안에서 획득돼 커밋/롤백 시 자동 해제된다.
 */
@Service
public class RecordPayoutService implements RecordPayoutUseCase {

    private final AcquireSellerLockPort acquireSellerLockPort;
    private final LoadAccountEntryPort loadAccountEntryPort;
    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public RecordPayoutService(AcquireSellerLockPort acquireSellerLockPort,
                               LoadAccountEntryPort loadAccountEntryPort,
                               RecordAccountEntryUseCase recordAccountEntryUseCase) {
        this.acquireSellerLockPort = acquireSellerLockPort;
        this.loadAccountEntryPort = loadAccountEntryPort;
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @Override
    @Transactional
    public void recordPayout(String sellerId, String payoutId, BigDecimal amount) {
        // LOW-1: 불량 payload(0·음수·null 금액)는 조용한 무전표 no-op 대신 명시적 거부해 DLT 로 보낸다.
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("payout 금액은 양수여야 합니다: " + amount);
        }
        // 잔액 읽기 전 셀러 단위 직렬화 락(HIGH — 무락 read-then-write 방어). 트랜잭션 종료 시 자동 해제.
        acquireSellerLockPort.lockSellerForPayout(sellerId);

        BigDecimal balance = loadAccountEntryPort.sellerPayableBalance(sellerId);
        BigDecimal payable = amount.min(balance.max(BigDecimal.ZERO));
        BigDecimal advance = amount.subtract(payable);

        if (payable.signum() > 0) {
            recordAccountEntryUseCase.record(AccountEntry.payoutCompleted(sellerId, payoutId, payable));
        }
        if (advance.signum() > 0) {
            recordAccountEntryUseCase.record(AccountEntry.payoutAdvanceReceivable(sellerId, payoutId, advance));
        }
    }
}
