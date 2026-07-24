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
 * 두 전표 합 = amount 라 CASH 유출 총액은 정확히 기록되며 어느 계정도 음수로 몰리지 않는다. 각 전기는
 * {@code RecordAccountEntryUseCase.record} 의 자연키 멱등 경로를 타고, 두 전표는 refType 으로 자연키가
 * 갈려(PAYOUT_COMPLETED·PAYOUT_ADVANCE, refId=payoutId 동일) 각각 멱등이다.
 *
 * <p><b>동시성(GL 감사 HIGH)</b>: 잔액 읽기 전에 셀러 단위 PG advisory xact 락({@link AcquireSellerLockPort})을
 * 잡아 같은 셀러의 동시 payout 을 직렬화한다 — 락이 없으면 둘째 payout 이 첫째의 미반영 잔액을 읽어 SELLER_PAYABLE
 * 을 음수로 몰고 초과분 회수채권을 놓친다(payoutId 파티셔닝 + concurrency>1 에서 실재현). xact 락이라 이
 * {@code @Transactional}(컨슈머 tx 조인) 안에서 획득돼 커밋/롤백 시 자동 해제된다.
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
