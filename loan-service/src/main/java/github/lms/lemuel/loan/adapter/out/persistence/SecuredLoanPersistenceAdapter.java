package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 담보/개인신용 대출 영속 어댑터.
 *
 * <p>담보는 별도 테이블이라 대출 환원 시 함께 조회해 주입한다. JPA 연관관계(@ManyToOne) 대신 FK 컬럼 +
 * 명시적 조회를 쓰는 것은 기존 어댑터들과 같은 방식으로, 도메인 객체 조립 지점을 어댑터 한 곳에 모아
 * 지연로딩이 도메인 계층으로 새어 나가지 않게 하기 위해서다.
 */
@Component
public class SecuredLoanPersistenceAdapter implements LoadSecuredLoanPort, SaveSecuredLoanPort {

    /** 연체 판정 대상 — 아직 잔액이 남아 상환이 진행 중인 상태들. */
    private static final List<SecuredLoanStatus> REPAYABLE_STATUSES =
            List.of(SecuredLoanStatus.DISBURSED, SecuredLoanStatus.OVERDUE);

    private final SecuredLoanRepository securedLoanRepository;
    private final CollateralRepository collateralRepository;

    public SecuredLoanPersistenceAdapter(SecuredLoanRepository securedLoanRepository,
                                         CollateralRepository collateralRepository) {
        this.securedLoanRepository = securedLoanRepository;
        this.collateralRepository = collateralRepository;
    }

    @Override
    public Optional<SecuredLoan> findById(Long loanId) {
        return securedLoanRepository.findById(loanId).map(this::toDomain);
    }

    @Override
    public Optional<SecuredLoan> findByIdForUpdate(Long loanId) {
        return securedLoanRepository.findByIdForUpdate(loanId).map(this::toDomain);
    }

    @Override
    public List<SecuredLoan> findByBorrower(Long borrowerUserId, int limit) {
        return securedLoanRepository
                .findByBorrowerUserIdOrderByIdDesc(borrowerUserId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<SecuredLoan> findRepayable() {
        return securedLoanRepository.findByStatusInOrderByIdAsc(REPAYABLE_STATUSES).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public SecuredLoan save(SecuredLoan loan) {
        SecuredLoanJpaEntity saved = securedLoanRepository.save(SecuredLoanJpaEntity.from(loan));
        return toDomain(saved);
    }

    @Override
    public Collateral saveCollateral(Collateral collateral) {
        return collateralRepository.save(CollateralJpaEntity.from(collateral)).toDomain();
    }

    /** 담보형이면 담보를 함께 조회해 도메인 객체를 완성한다. */
    private SecuredLoan toDomain(SecuredLoanJpaEntity entity) {
        Collateral collateral = entity.getCollateralId() == null
                ? null
                : collateralRepository.findById(entity.getCollateralId())
                        .map(CollateralJpaEntity::toDomain)
                        .orElse(null);
        return entity.toDomain(collateral);
    }
}
