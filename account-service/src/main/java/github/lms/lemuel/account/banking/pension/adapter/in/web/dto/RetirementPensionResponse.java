package github.lms.lemuel.account.banking.pension.adapter.in.web.dto;

import github.lms.lemuel.account.banking.pension.domain.BenefitType;
import github.lms.lemuel.account.banking.pension.domain.ContributionSource;
import github.lms.lemuel.account.banking.pension.domain.MidWithdrawalReason;
import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.banking.pension.domain.PensionStatus;
import github.lms.lemuel.account.banking.pension.domain.PensionTransaction;
import github.lms.lemuel.account.banking.pension.domain.PensionTransactionType;
import github.lms.lemuel.account.banking.pension.domain.RetirementPension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 퇴직연금 계약 응답.
 *
 * <p>{@code nextSeq} 를 그대로 노출한다 — 다음 거래가 어떤 GL 전표 자연키
 * ({@code RP-{id}-{nextSeq}})로 기표될지 밖에서도 예측할 수 있어야 대사(reconciliation)가 쉬워진다.
 *
 * <p>{@code lastInterestSettledOn} 도 노출한다 — 다음 이자 확정이 어느 날부터 기산되는지가 곧
 * 산출 금액의 근거이고, 그 근거가 응답에 없으면 가입자가 서버 산출 이자를 검증할 방법이 없다.
 */
public record RetirementPensionResponse(
        Long id,
        String subscriberId,
        PensionScheme scheme,
        String employerName,
        LocalDate birthDate,
        BigDecimal annualRate,
        String productName,
        BigDecimal productRate,
        PensionStatus status,
        LocalDate openedOn,
        LocalDate lastInterestSettledOn,
        LocalDate benefitStartedOn,
        BenefitType benefitType,
        BigDecimal accumulatedAmount,
        long nextSeq,
        List<TransactionView> transactions) {

    public record TransactionView(long seq, PensionTransactionType type, BigDecimal amount,
                                  ContributionSource contributionSource,
                                  MidWithdrawalReason midWithdrawalReason, LocalDate occurredOn) {

        static TransactionView from(PensionTransaction transaction) {
            return new TransactionView(transaction.getSeq(), transaction.getType(), transaction.getAmount(),
                    transaction.getContributionSource(), transaction.getMidWithdrawalReason(),
                    transaction.getOccurredOn());
        }
    }

    public static RetirementPensionResponse from(RetirementPension pension) {
        return new RetirementPensionResponse(
                pension.getId(),
                pension.getSubscriberId(),
                pension.getScheme(),
                pension.getEmployerName(),
                pension.getBirthDate(),
                pension.getAnnualRate(),
                pension.getPrincipalGuaranteedProduct().productName(),
                pension.getPrincipalGuaranteedProduct().rate(),
                pension.getStatus(),
                pension.getOpenedOn(),
                pension.getLastInterestSettledOn(),
                pension.getBenefitStartedOn(),
                pension.getBenefitType(),
                pension.getAccumulatedAmount(),
                pension.getNextSeq(),
                pension.getTransactions().stream().map(TransactionView::from).toList());
    }
}
