package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.in.OffsetSellerRecoveryUseCase;
import github.lms.lemuel.settlement.application.port.in.ApplyLoanDeductionUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.RecordLoanDeductionPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import github.lms.lemuel.tax.application.WithholdingResolution;
import github.lms.lemuel.tax.application.port.in.ResolveSettlementWithholdingUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 상환 saga 의 settlement 측 종착점: loan 차감을 기록하고 <b>즉시지급 Payout 을 생성</b>한다.
 *
 * <p><b>왜 지급 생성이 확정 배치가 아니라 여기인가(L-3)</b>: 확정 시점에 금액을 확정하면 뒤늦게 도착하는
 * 대출 차감을 반영할 방법이 없다({@code Payout.amount} 는 final). 실제로 그 상태였고, 결과는
 * "loan 은 대출 잔액을 줄이는데 현금은 셀러에게 전액 나간다" 였다 — 채권만 사라졌다.
 * loan 이 <b>차감 0 에도 항상</b> {@code repayment_applied} 를 발행하므로 모든 정산에 트리거가 온다.
 *
 * <p><b>차감 순서</b>(정본: 📘settlement-domain-rules "지급액 차감 순서"):
 * <pre>
 * payout = max(0, immediate − 원천징수 − 대출 상환차감 − 채권상계)
 * </pre>
 * 기준은 "못 뗐을 때 이월되는가" 하나다. ① 원천징수는 이월 장치가 없어 소실(과소징수)이고,
 * ② 대출 차감은 loan 이 이미 상환을 기록해 못 떼면 손실이며, ③ 채권상계만 OPEN 으로 남아 이월된다.
 * 그래서 상계에는 <b>앞의 둘을 뺀 잔여</b>만 가용액으로 넘긴다.
 *
 * <p><b>이벤트 유실 시</b>: 지급이 생성되지 않는다. 감지는 INV-6 지급 대사의 "payout 없는 정산" 목록이며,
 * 시간 기반 경보 격상은 후속 과제다(역산 PRD 참조).
 */
@Service
public class ApplyLoanDeductionService implements ApplyLoanDeductionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyLoanDeductionService.class);

    private final RecordLoanDeductionPort recordLoanDeductionPort;
    private final LoadSettlementPort loadSettlementPort;
    private final ResolveSettlementWithholdingUseCase resolveSettlementWithholdingUseCase;
    private final OffsetSellerRecoveryUseCase offsetSellerRecoveryUseCase;
    private final RequestPayoutUseCase requestPayoutUseCase;

    public ApplyLoanDeductionService(RecordLoanDeductionPort recordLoanDeductionPort,
                                     LoadSettlementPort loadSettlementPort,
                                     ResolveSettlementWithholdingUseCase resolveSettlementWithholdingUseCase,
                                     OffsetSellerRecoveryUseCase offsetSellerRecoveryUseCase,
                                     RequestPayoutUseCase requestPayoutUseCase) {
        this.recordLoanDeductionPort = recordLoanDeductionPort;
        this.loadSettlementPort = loadSettlementPort;
        this.resolveSettlementWithholdingUseCase = resolveSettlementWithholdingUseCase;
        this.offsetSellerRecoveryUseCase = offsetSellerRecoveryUseCase;
        this.requestPayoutUseCase = requestPayoutUseCase;
    }

    @Override
    @Transactional
    public void apply(long settlementId, long sellerId, BigDecimal deducted) {
        recordLoanDeductionPort.record(settlementId, sellerId, deducted);

        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "정산을 찾을 수 없습니다. settlementId=" + settlementId));

        // getImmediatePayoutAmount() 가 아니라 Base 다 — 해제된 정산을 재구동할 때 net 전액이 나가면
        // HOLDBACK_RELEASE payout 과 합쳐 net 을 초과한다(INV-6 이중 지급).
        BigDecimal immediate = settlement.getImmediatePayoutBase();

        // ① 원천징수 — 확정 배치가 같은 입력(net)으로 산출·발행한 값과 동일해야 account GL 통제계정이
        //    0 으로 닫힌다. 입력이 같으므로 재계산 결과도 같다(결정적 순수 함수).
        WithholdingResolution withholding =
                resolveSettlementWithholdingUseCase.resolveForPayout(sellerId, settlement.getNetAmount());
        BigDecimal effectiveWithholding = immediate.min(withholding.withholdingAmount());

        // ② 대출 상환차감 — loan 이 이미 잔액을 줄이고 상환을 기록했다. 여기서 못 빼면 채권만 사라진다.
        BigDecimal afterLoan = immediate.subtract(effectiveWithholding).subtract(deducted)
                .max(BigDecimal.ZERO);

        // ③ 채권상계 — 앞의 둘을 뺀 잔여까지만. 이 순서를 뒤집으면 상계가 세금·대출 재원을 잠식한다.
        BigDecimal offset = offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(
                settlementId, sellerId, afterLoan, settlement.getSettlementDate());

        BigDecimal payoutAmount = afterLoan.subtract(offset).max(BigDecimal.ZERO);

        requestPayoutUseCase.requestPayoutOfType(settlementId, sellerId, payoutAmount, PayoutType.IMMEDIATE);

        log.info("[LoanDeduction] 반영·지급요청: settlementId={}, sellerId={}, immediate={}, 원천징수={}, "
                        + "대출차감={}, 채권상계={}, 지급={}",
                settlementId, sellerId, immediate, effectiveWithholding, deducted, offset, payoutAmount);
    }

    @Override
    @Transactional
    public boolean redriveFromRecordedDeduction(long settlementId, long sellerId) {
        Optional<BigDecimal> recorded = recordLoanDeductionPort.findDeduction(settlementId);
        if (recorded.isEmpty()) {
            // loan 이벤트가 아직/영영 도착하지 않았다. 차감액을 모르는 채 지급하면 대출채권이 사라진다.
            // 여기서 0 으로 가정하는 것이 정확히 그 사고다 — 지급하지 않고 미해결로 남긴다.
            log.warn("[LoanDeduction] 재구동 불가 — 차감 기록 없음(loan 이벤트 미도착). "
                    + "settlementId={}, sellerId={}", settlementId, sellerId);
            return false;
        }
        apply(settlementId, sellerId, recorded.get());
        return true;
    }
}
