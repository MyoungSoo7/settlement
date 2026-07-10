package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.exception.NotInvestableException;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentScore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

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

    public PlaceInvestmentOrderService(GetInvestmentScoreUseCase getInvestmentScoreUseCase,
                                       LoadFundingViewPort loadFundingViewPort,
                                       LoadInvestmentOrderPort loadInvestmentOrderPort,
                                       SaveInvestmentOrderPort saveInvestmentOrderPort) {
        this.getInvestmentScoreUseCase = getInvestmentScoreUseCase;
        this.loadFundingViewPort = loadFundingViewPort;
        this.loadInvestmentOrderPort = loadInvestmentOrderPort;
        this.saveInvestmentOrderPort = saveInvestmentOrderPort;
    }

    @Override
    @Transactional
    public InvestmentOrder place(PlaceInvestmentOrderCommand command) {
        InvestmentScore score = getInvestmentScoreUseCase.getScore(command.stockCode());
        if (!score.investable()) {
            throw new NotInvestableException(
                    "투자 부적격 종목입니다. stockCode=" + command.stockCode()
                            + ", score=" + score.totalScore() + ", grade=" + score.grade());
        }

        BigDecimal available = availableFunding(command.sellerId());
        if (available.compareTo(command.amount()) < 0) {
            throw new InsufficientFundingException(
                    "가용 재원이 부족합니다. available=" + available + ", requested=" + command.amount());
        }

        InvestmentOrder order = InvestmentOrder.request(
                command.sellerId(), command.stockCode(), command.amount(),
                score.totalScore(), score.grade().name());
        return saveInvestmentOrderPort.save(order);
    }

    private BigDecimal availableFunding(Long sellerId) {
        BigDecimal confirmed = loadFundingViewPort.sumConfirmedBySeller(sellerId);
        BigDecimal invested = loadInvestmentOrderPort.sumExecutedAmountBySeller(sellerId);
        return confirmed.subtract(invested);
    }
}
