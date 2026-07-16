package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.exception.NotInvestableException;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.out.InvestmentMetricsPort;
import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentScore;
import github.lms.lemuel.investment.domain.SellerFunding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 투자 주문 신청: 종목 투자점수를 조회해 적격(investable)인지 검증하고, 셀러의 가용 재원이
 * 신청액 이상인지 확인한 뒤 REQUESTED 상태로 저장한다. 실제 집행은 {@link ExecuteInvestmentOrderService}
 * 에서 재원을 재검증한 뒤 이뤄진다.
 *
 * <ul>
 *   <li>부적격 종목(총점 &lt;60) → {@link NotInvestableException}(→422)</li>
 *   <li>가용 재원 부족 → {@link InsufficientFundingException}(→422)</li>
 * </ul>
 */
@Service
public class PlaceInvestmentOrderService implements PlaceInvestmentOrderUseCase {

    private final GetInvestmentScoreUseCase getInvestmentScoreUseCase;
    private final LoadFundingViewPort loadFundingViewPort;
    private final LoadInvestmentOrderPort loadInvestmentOrderPort;
    private final SaveInvestmentOrderPort saveInvestmentOrderPort;
    private final InvestmentMetricsPort investmentMetricsPort;
    private final Clock clock;

    public PlaceInvestmentOrderService(GetInvestmentScoreUseCase getInvestmentScoreUseCase,
                                       LoadFundingViewPort loadFundingViewPort,
                                       LoadInvestmentOrderPort loadInvestmentOrderPort,
                                       SaveInvestmentOrderPort saveInvestmentOrderPort,
                                       InvestmentMetricsPort investmentMetricsPort,
                                       Clock clock) {
        this.getInvestmentScoreUseCase = getInvestmentScoreUseCase;
        this.loadFundingViewPort = loadFundingViewPort;
        this.loadInvestmentOrderPort = loadInvestmentOrderPort;
        this.saveInvestmentOrderPort = saveInvestmentOrderPort;
        this.investmentMetricsPort = investmentMetricsPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    @Auditable(
            action = AuditAction.INVESTMENT_ORDER_PLACED,
            failureAction = "INVESTMENT_ORDER_REJECTED",
            resourceType = "InvestmentOrder",
            resourceId = "#result == null ? null : #result.getId().toString()",
            detail = "{'sellerId': #p0.sellerId(), 'stockCode': #p0.stockCode(), 'amount': #p0.amount()}"
    )
    public InvestmentOrder place(PlaceInvestmentOrderCommand command) {
        InvestmentScore score = getInvestmentScoreUseCase.getScore(command.stockCode());
        if (!score.investable()) {
            investmentMetricsPort.orderPlacementRejected("NOT_INVESTABLE");
            throw new NotInvestableException(
                    "투자 부적격 종목입니다. stockCode=" + command.stockCode()
                            + ", score=" + score.totalScore() + ", grade=" + score.grade());
        }

        // 신청액을 도메인 진입 시점에 scale 2 HALF_UP 로 정규화 — funding 판정·저장·응답 기준을 일치시킨다.
        BigDecimal amount = normalize(command.amount());

        SellerFunding funding = loadFunding(command.sellerId());
        if (!funding.covers(amount)) {
            investmentMetricsPort.orderPlacementRejected("INSUFFICIENT_FUNDING");
            throw new InsufficientFundingException(
                    "가용 재원이 부족합니다. available=" + funding.available() + ", requested=" + amount,
                    amount, funding.available());
        }

        InvestmentOrder order = InvestmentOrder.request(
                command.sellerId(), command.stockCode(), amount,
                score.totalScore(), score.grade().name(), LocalDateTime.now(clock));
        InvestmentOrder saved = saveInvestmentOrderPort.save(order);
        investmentMetricsPort.orderPlaced();
        return saved;
    }

    /** null 은 도메인 불변식(request)에 위임하고, 값이 있으면 통화 정규화(scale 2 HALF_UP)한다. */
    private static BigDecimal normalize(BigDecimal amount) {
        return amount == null ? null : Money.of(amount).toBigDecimal();
    }

    private SellerFunding loadFunding(Long sellerId) {
        // ★ FOR UPDATE 로 재원 행을 잡아 같은 셀러 동시 신청을 직렬화한다(집행 경로와 동형 write-skew 방지).
        BigDecimal confirmed = loadFundingViewPort.sumConfirmedBySellerForUpdate(sellerId);
        BigDecimal invested = loadInvestmentOrderPort.sumExecutedAmountBySeller(sellerId);
        return SellerFunding.of(sellerId, confirmed, invested);
    }
}
