package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.SimulateCommissionRateUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadCommissionRatePolicyPort;
import github.lms.lemuel.settlement.domain.CommissionRatePolicy;
import github.lms.lemuel.settlement.domain.SellerTier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 요율 해석 미리보기.
 *
 * <p>정산 생성 경로와 <b>같은 도메인 함수</b>({@code CommissionRatePolicy.resolve})를 쓴다 —
 * 미리보기가 자체 계산을 두면 "시뮬레이션은 1.8%였는데 실제로는 2.5%로 정산됐다"가 된다.
 */
@Service
@Transactional(readOnly = true)
public class SimulateCommissionRateService implements SimulateCommissionRateUseCase {

    private final LoadCommissionRatePolicyPort loadPort;

    public SimulateCommissionRateService(LoadCommissionRatePolicyPort loadPort) {
        this.loadPort = loadPort;
    }

    @Override
    public RateSimulation simulate(Long sellerId, SellerTier tier, LocalDate at) {
        SellerTier effectiveTier = tier == null ? SellerTier.NORMAL : tier;
        LocalDate date = at == null ? LocalDate.now() : at;

        var resolved = CommissionRatePolicy.resolve(
                loadPort.findEffectiveCandidates(sellerId, effectiveTier, date),
                effectiveTier, sellerId, date);
        return new RateSimulation(sellerId, effectiveTier.name(), date,
                resolved.rate(), resolved.sourceLabel());
    }
}
