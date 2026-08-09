package github.lms.lemuel.account.banking.pension.adapter.out.persistence;

import github.lms.lemuel.account.banking.pension.domain.BenefitType;
import github.lms.lemuel.account.banking.pension.domain.ContributionSource;
import github.lms.lemuel.account.banking.pension.domain.MidWithdrawalReason;
import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.banking.pension.domain.PensionStatus;
import github.lms.lemuel.account.banking.pension.domain.PensionTransaction;
import github.lms.lemuel.account.banking.pension.domain.PensionTransactionType;
import github.lms.lemuel.account.banking.pension.domain.PrincipalGuaranteedProduct;
import github.lms.lemuel.account.banking.pension.domain.RetirementPension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 퇴직연금 영속 어댑터 테스트 — append-only 규약과 N+1 회피 경로를 리포지토리 목으로 고정한다.
 *
 * <p>가장 중요한 검증은 "이미 저장된 거래를 다시 쓰지 않는다"는 것이다. 거래를 통째로 재저장하면
 * 같은 {@code (pension_id, seq)} 로 두 번째 행을 만들려다 UNIQUE 에 걸리거나, 최악의 경우 이력이
 * 덮어써진다.
 */
@ExtendWith(MockitoExtension.class)
class RetirementPensionPersistenceAdapterTest {

    private static final LocalDate OPENED = LocalDate.of(2020, 1, 1);
    private static final LocalDate BIRTH = LocalDate.of(1966, 3, 2);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
    private static final BigDecimal RATE = new BigDecimal("0.035000");

    @Mock RetirementPensionRepository pensionRepository;
    @Mock PensionTransactionRepository transactionRepository;

    @Captor ArgumentCaptor<List<PensionTransactionJpaEntity>> appendedCaptor;

    @InjectMocks RetirementPensionPersistenceAdapter adapter;

    @Test
    void 저장은_식별자가_없는_거래만_새로_적재한다() {
        RetirementPension pension = RetirementPension.reconstitute(5L, "77", PensionScheme.DC, "레무엘테크", BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE), PensionStatus.ACCUMULATING,
                OPENED, null, null, null, new BigDecimal("1000000"), 2L,
                List.of(PensionTransaction.reconstitute(11L, 1L, PensionTransactionType.CONTRIBUTION,
                        new BigDecimal("1000000"), ContributionSource.EMPLOYER, null, OPENED)));
        pension.contribute(new BigDecimal("500000"), ContributionSource.EMPLOYEE, TODAY);

        when(pensionRepository.save(any())).thenReturn(pensionEntity(5L, PensionStatus.ACCUMULATING));
        when(transactionRepository.findByPensionIdOrderBySeqAsc(5L))
                .thenReturn(List.of(transactionEntity(11L, 1L, PensionTransactionType.CONTRIBUTION,
                        "1000000", ContributionSource.EMPLOYER, null)));
        when(transactionRepository.saveAll(anyList()))
                .thenReturn(List.of(transactionEntity(12L, 2L, PensionTransactionType.CONTRIBUTION,
                        "500000", ContributionSource.EMPLOYEE, null)));

        RetirementPension saved = adapter.save(pension);

        verify(transactionRepository).saveAll(appendedCaptor.capture());
        assertThat(appendedCaptor.getValue()).singleElement().satisfies(entity -> {
            assertThat(entity.getId()).isNull();
            assertThat(entity.getPensionId()).isEqualTo(5L);
            assertThat(entity.getSeq()).isEqualTo(2L);
            assertThat(entity.getAmount()).isEqualByComparingTo("500000");
            assertThat(entity.getContributionSource()).isEqualTo(ContributionSource.EMPLOYEE);
            assertThat(entity.getMidWithdrawalReason()).isNull();
            assertThat(entity.getOccurredOn()).isEqualTo(TODAY);
        });
        assertThat(saved.getTransactions()).extracting(PensionTransaction::getSeq).containsExactly(1L, 2L);
    }

    @Test
    void 새_거래가_없으면_거래_저장을_호출하지_않는다() {
        RetirementPension pension = RetirementPension.reconstitute(5L, "77", PensionScheme.DC, "레무엘테크", BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE), PensionStatus.ACCUMULATING,
                OPENED, null, null, null, new BigDecimal("1000000"), 2L,
                List.of(PensionTransaction.reconstitute(11L, 1L, PensionTransactionType.CONTRIBUTION,
                        new BigDecimal("1000000"), ContributionSource.EMPLOYER, null, OPENED)));

        when(pensionRepository.save(any())).thenReturn(pensionEntity(5L, PensionStatus.ACCUMULATING));
        when(transactionRepository.findByPensionIdOrderBySeqAsc(5L))
                .thenReturn(List.of(transactionEntity(11L, 1L, PensionTransactionType.CONTRIBUTION,
                        "1000000", ContributionSource.EMPLOYER, null)));

        RetirementPension saved = adapter.save(pension);

        verify(transactionRepository, never()).saveAll(anyList());
        assertThat(saved.getTransactions()).hasSize(1);
    }

    @Test
    void 계약_본체_매핑은_운용지시를_두_컬럼으로_펼친다() {
        RetirementPension pension = RetirementPension.reconstitute(5L, "77", PensionScheme.DB, "레무엘테크", BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE), PensionStatus.RECEIVING,
                OPENED, null, TODAY, BenefitType.ANNUITY, new BigDecimal("1000000"), 3L, List.of());

        when(pensionRepository.save(any())).thenReturn(pensionEntity(5L, PensionStatus.RECEIVING));
        when(transactionRepository.findByPensionIdOrderBySeqAsc(5L)).thenReturn(List.of());

        adapter.save(pension);

        ArgumentCaptor<RetirementPensionJpaEntity> captor =
                ArgumentCaptor.forClass(RetirementPensionJpaEntity.class);
        verify(pensionRepository).save(captor.capture());
        RetirementPensionJpaEntity entity = captor.getValue();
        assertThat(entity.getId()).isEqualTo(5L);
        assertThat(entity.getSubscriberId()).isEqualTo("77");
        assertThat(entity.getScheme()).isEqualTo(PensionScheme.DB);
        assertThat(entity.getEmployerName()).isEqualTo("레무엘테크");
        assertThat(entity.getProductName()).isEqualTo("정기예금형");
        assertThat(entity.getProductRate()).isEqualByComparingTo("0.035");
        assertThat(entity.getAnnualRate()).isEqualByComparingTo("0.035");
        assertThat(entity.getStatus()).isEqualTo(PensionStatus.RECEIVING);
        assertThat(entity.getBenefitStartedOn()).isEqualTo(TODAY);
        assertThat(entity.getBenefitType()).isEqualTo(BenefitType.ANNUITY);
        assertThat(entity.getAccumulatedAmount()).isEqualByComparingTo("1000000");
        assertThat(entity.getNextSeq()).isEqualTo(3L);
    }

    @Test
    void 단건_복원은_거래_종류별_부가정보를_그대로_되살린다() {
        when(pensionRepository.findById(5L))
                .thenReturn(Optional.of(pensionEntity(5L, PensionStatus.ACCUMULATING)));
        when(transactionRepository.findByPensionIdOrderBySeqAsc(5L)).thenReturn(List.of(
                transactionEntity(11L, 1L, PensionTransactionType.CONTRIBUTION,
                        "1000000", ContributionSource.EMPLOYER, null),
                transactionEntity(12L, 2L, PensionTransactionType.MID_WITHDRAWAL,
                        "300000", null, MidWithdrawalReason.BANKRUPTCY)));

        RetirementPension pension = adapter.findById(5L).orElseThrow();

        assertThat(pension.getId()).isEqualTo(5L);
        assertThat(pension.getSubscriberId()).isEqualTo("77");
        assertThat(pension.getPrincipalGuaranteedProduct().productName()).isEqualTo("정기예금형");
        assertThat(pension.getTransactions()).hasSize(2);
        assertThat(pension.getTransactions().get(0).getContributionSource())
                .isEqualTo(ContributionSource.EMPLOYER);
        assertThat(pension.getTransactions().get(1).getMidWithdrawalReason())
                .isEqualTo(MidWithdrawalReason.BANKRUPTCY);
        assertThat(pension.getTransactions().get(1).getId()).isEqualTo(12L);
    }

    @Test
    void 없는_계약은_빈_결과로_돌려준다() {
        when(pensionRepository.findById(404L)).thenReturn(Optional.empty());

        assertThat(adapter.findById(404L)).isEmpty();
        verify(transactionRepository, never()).findByPensionIdOrderBySeqAsc(any());
    }

    @Test
    void 목록_조회는_거래를_한_번에_끌어와_계약별로_묶는다() {
        when(pensionRepository.findBySubscriberIdOrderByIdAsc("77")).thenReturn(List.of(
                pensionEntity(5L, PensionStatus.ACCUMULATING),
                pensionEntity(6L, PensionStatus.ACCUMULATING)));
        when(transactionRepository.findByPensionIdInOrderBySeqAsc(List.of(5L, 6L))).thenReturn(List.of(
                transactionEntity(11L, 1L, 5L, PensionTransactionType.CONTRIBUTION,
                        "1000000", ContributionSource.EMPLOYER, null),
                transactionEntity(12L, 1L, 6L, PensionTransactionType.CONTRIBUTION,
                        "700000", ContributionSource.EMPLOYER, null),
                transactionEntity(13L, 2L, 6L, PensionTransactionType.INTEREST, "3000", null, null)));

        List<RetirementPension> pensions = adapter.findBySubscriberId("77");

        assertThat(pensions).hasSize(2);
        assertThat(pensions.get(0).getTransactions()).hasSize(1);
        assertThat(pensions.get(1).getTransactions()).extracting(PensionTransaction::getSeq)
                .containsExactly(1L, 2L);
        verify(transactionRepository, never()).findByPensionIdOrderBySeqAsc(any());
    }

    @Test
    void 거래가_없는_계약도_빈_이력으로_복원된다() {
        when(pensionRepository.findBySubscriberIdOrderByIdAsc("77"))
                .thenReturn(List.of(pensionEntity(5L, PensionStatus.ACCUMULATING)));
        when(transactionRepository.findByPensionIdInOrderBySeqAsc(List.of(5L))).thenReturn(List.of());

        assertThat(adapter.findBySubscriberId("77")).singleElement()
                .satisfies(pension -> assertThat(pension.getTransactions()).isEmpty());
    }

    @Test
    void 계약이_하나도_없으면_거래_조회를_아예_하지_않는다() {
        when(pensionRepository.findBySubscriberIdOrderByIdAsc("99")).thenReturn(List.of());

        assertThat(adapter.findBySubscriberId("99")).isEmpty();
        verify(transactionRepository, never()).findByPensionIdInOrderBySeqAsc(anyList());
    }

    // ---------------------------------------------------------------- 픽스처

    private static RetirementPensionJpaEntity pensionEntity(Long id, PensionStatus status) {
        return new RetirementPensionJpaEntity(id, "77", PensionScheme.DC, "레무엘테크", BIRTH, RATE,
                "정기예금형", RATE, status, OPENED, null,
                status == PensionStatus.RECEIVING ? TODAY : null,
                status == PensionStatus.RECEIVING ? BenefitType.ANNUITY : null,
                new BigDecimal("1000000"), 3L);
    }

    private static PensionTransactionJpaEntity transactionEntity(Long id, long seq, PensionTransactionType type,
                                                                 String amount, ContributionSource source,
                                                                 MidWithdrawalReason reason) {
        return transactionEntity(id, seq, 5L, type, amount, source, reason);
    }

    private static PensionTransactionJpaEntity transactionEntity(Long id, long seq, Long pensionId,
                                                                 PensionTransactionType type, String amount,
                                                                 ContributionSource source,
                                                                 MidWithdrawalReason reason) {
        return new PensionTransactionJpaEntity(id, pensionId, seq, type, new BigDecimal(amount),
                source, reason, TODAY);
    }
}
