package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.CollateralRiskPort;
import github.lms.lemuel.loan.domain.CollateralRevaluation;
import github.lms.lemuel.loan.domain.MarginCall;
import github.lms.lemuel.loan.domain.MarginCallStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 담보 재평가 이력 · 마진콜 영속 어댑터.
 *
 * <p>최신 평가액은 <b>재평가 이력이 있으면 그 값, 없으면 설정 시점 평가액</b>이다. 이 폴백을 어댑터가
 * 담당하므로 응용 계층은 "최신 값"만 물어보면 되고, 재평가 이력의 유무를 신경 쓰지 않는다.
 */
@Component
public class CollateralRiskPersistenceAdapter implements CollateralRiskPort {

    private final CollateralRevaluationRepository revaluationRepository;
    private final MarginCallRepository marginCallRepository;
    private final CollateralRepository collateralRepository;

    public CollateralRiskPersistenceAdapter(CollateralRevaluationRepository revaluationRepository,
                                            MarginCallRepository marginCallRepository,
                                            CollateralRepository collateralRepository) {
        this.revaluationRepository = revaluationRepository;
        this.marginCallRepository = marginCallRepository;
        this.collateralRepository = collateralRepository;
    }

    @Override
    public CollateralRevaluation appendRevaluation(CollateralRevaluation revaluation) {
        return revaluationRepository.save(CollateralRevaluationJpaEntity.from(revaluation)).toDomain();
    }

    @Override
    public Optional<BigDecimal> findLatestValue(Long collateralId) {
        Optional<BigDecimal> revalued = revaluationRepository
                .findFirstByCollateralIdOrderByRevaluedAtDescIdDesc(collateralId)
                .map(CollateralRevaluationJpaEntity::getRevaluedValue);
        if (revalued.isPresent()) {
            return revalued;
        }
        // 재평가 이력이 없으면 설정 시점 평가액이 곧 최신 값이다.
        return collateralRepository.findById(collateralId)
                .map(CollateralJpaEntity::toDomain)
                .map(c -> c.getAppraisedValue());
    }

    @Override
    public Optional<MarginCall> findOpenMarginCall(Long loanId) {
        return marginCallRepository.findByLoanIdAndStatus(loanId, MarginCallStatus.OPEN)
                .map(MarginCallJpaEntity::toDomain);
    }

    @Override
    public MarginCall saveMarginCall(MarginCall marginCall) {
        return marginCallRepository.save(MarginCallJpaEntity.from(marginCall)).toDomain();
    }
}
