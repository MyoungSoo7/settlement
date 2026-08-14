package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveGeneralPayoutPort;
import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.GeneralPayoutCalculator;
import github.lms.lemuel.insurance.domain.GeneralPayoutCalculator.PayoutQuote;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * 일반지급 생성기 (D-G1) — "어떤 Policy 전이가 어떤 지급을 낳는가"의 단일 결정 지점.
 *
 * <p>해지/철회 유스케이스({@code PolicyTerminationService})와 만기·실효소멸 배치
 * ({@code PolicyExpiryService})가 공유한다. 전이 매핑:
 * <ul>
 *   <li>→ SURRENDERED : 해약환급금 (기준일 = 해지일)</li>
 *   <li>ACTIVE → EXPIRED : 만기보험금 (만기 전일까지 납입 가정)</li>
 *   <li>LAPSED → EXPIRED : 해약환급금 (기준일 = 실효일 — 실효 이후 납입 없음)</li>
 *   <li>→ CANCELLED : 철회환급금 (기납입 전액)</li>
 * </ul>
 *
 * <p>환급액 0(경과 12개월 미만 해지)이면 payout 을 만들지 않는다 (D-G3).
 * 생성 시 {@code general_payout_requested} 를 같은 tx 의 Outbox 에 기록한다.
 *
 * <p><b>{@code MANDATORY}</b>: 반드시 호출자의 트랜잭션 안에서만 돈다. payout INSERT 와
 * Outbox 기록이 계약 전이와 다른 tx 로 갈라지면 "해지됐는데 환급금이 없는" 상태가 커밋될 수
 * 있다 — 트랜잭션 없이 호출되면 조용히 새 tx 를 여는 대신 즉시 실패시킨다.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class GeneralPayoutRecorder {

    private static final Logger log = LoggerFactory.getLogger(GeneralPayoutRecorder.class);

    private final LoadPolicyPort loadPolicyPort;
    private final SaveGeneralPayoutPort savePayoutPort;
    private final PublishInsuranceEventPort publishPort;

    public GeneralPayoutRecorder(
            LoadPolicyPort loadPolicyPort,
            SaveGeneralPayoutPort savePayoutPort,
            PublishInsuranceEventPort publishPort) {
        this.loadPolicyPort = loadPolicyPort;
        this.savePayoutPort = savePayoutPort;
        this.publishPort = publishPort;
    }

    /**
     * terminal 전이 직후 호출된다 — 호출자의 트랜잭션에 참여한다.
     *
     * @param policy         전이가 반영된 계약 (terminal 상태)
     * @param previousStatus 전이 전 상태 — EXPIRED 의 만기/실효소멸 구분에 필요
     * @param today          전이 발생일 (KST)
     * @return 생성된 payout — 환급액 0 이면 empty
     */
    public Optional<GeneralPayout> recordFor(Policy policy, PolicyStatus previousStatus, LocalDate today) {
        int cycleMonths = loadPolicyPort.findPaymentCycleMonths(policy.getPolicyId())
                .orElseThrow(() -> new IllegalStateException(
                        "납입주기를 찾을 수 없습니다: policyNumber=" + policy.getPolicyNumber()));

        PayoutBirth birth = resolve(policy, previousStatus, today);
        PayoutQuote quote = quote(policy, birth, cycleMonths);

        if (quote.amount().signum() <= 0) {
            // D-G3: 경과 12개월 미만 해지 등 — 0원 지급 행은 만들지 않는다
            log.info("[GeneralPayout] 환급액 0 — payout 미생성: policyNumber={} type={} 경과={}개월",
                    policy.getPolicyNumber(), birth.type(), quote.elapsedMonths());
            return Optional.empty();
        }

        GeneralPayout payout = GeneralPayout.request(
                policy.getPolicyId(), policy.getPolicyNumber(), birth.type(), quote, today);
        GeneralPayout saved = savePayoutPort.insert(payout);
        publishPort.publishGeneralPayoutRequested(policy, saved);
        log.info("[GeneralPayout] 지급 요청: policyNumber={} type={} amount={}",
                policy.getPolicyNumber(), birth.type(), quote.amount().toPlainString());
        return Optional.of(saved);
    }

    private PayoutQuote quote(Policy policy, PayoutBirth birth, int cycleMonths) {
        return switch (birth.type()) {
            case SURRENDER_REFUND -> GeneralPayoutCalculator.surrenderRefund(
                    policy.getPremiumAmount(), cycleMonths, policy.getEffectiveDate(), birth.endDate());
            case MATURITY_BENEFIT -> GeneralPayoutCalculator.maturityBenefit(
                    policy.getPremiumAmount(), cycleMonths, policy.getEffectiveDate(), birth.endDate());
            case WITHDRAWAL_REFUND -> GeneralPayoutCalculator.withdrawalRefund(
                    policy.getPremiumAmount(), cycleMonths, policy.getEffectiveDate(), birth.endDate());
        };
    }

    /** D-G1: terminal 상태 × 직전 상태 → (지급 유형, 산출 기준일). */
    private PayoutBirth resolve(Policy policy, PolicyStatus previousStatus, LocalDate today) {
        return switch (policy.getStatus()) {
            case SURRENDERED -> new PayoutBirth(GeneralPayoutType.SURRENDER_REFUND, today);
            case EXPIRED -> previousStatus == PolicyStatus.ACTIVE
                    // 만기소멸 — 배치가 늦게 돌아도 산출 기준은 만기일이다
                    ? new PayoutBirth(GeneralPayoutType.MATURITY_BENEFIT,
                            Objects.requireNonNull(policy.getMaturityDate(),
                                    "만기소멸 계약의 maturityDate 는 null 일 수 없습니다"))
                    // 실효소멸 — 실효 이후 납입이 없으므로 실효일 기준 해약환급금
                    : new PayoutBirth(GeneralPayoutType.SURRENDER_REFUND,
                            Objects.requireNonNull(policy.getLapsedAt(),
                                    "실효소멸 계약의 lapsedAt 은 null 일 수 없습니다"));
            case CANCELLED -> new PayoutBirth(GeneralPayoutType.WITHDRAWAL_REFUND,
                    policy.getLapsedAt() != null ? policy.getLapsedAt() : today);
            default -> throw new InvalidGeneralPayoutException(
                    "일반지급 트리거가 아닌 상태: " + policy.getStatus());
        };
    }

    private record PayoutBirth(GeneralPayoutType type, LocalDate endDate) {
    }
}
