package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 계약별 일반지급 내역 조회 유스케이스 (§14) — 산출근거 스냅샷(D-G5) 포함.
 */
public interface GetPolicyPayoutsUseCase {

    /**
     * 증권번호로 일반지급 내역을 조회한다.
     *
     * @throws github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException 계약이 없으면
     */
    List<GeneralPayoutSummary> byPolicyNumber(String policyNumber);

    /**
     * 일반지급 1건 요약 — 지급액과 산출근거(기납입합계·적용요율·경과월수·납입회차수)까지.
     */
    record GeneralPayoutSummary(String payoutId, GeneralPayoutType payoutType, BigDecimal amount,
                                GeneralPayoutStatus status, LocalDate requestedOn, LocalDate paidOn,
                                BigDecimal paidPremiumTotal, BigDecimal appliedRate,
                                long elapsedMonths, int installmentCount) {

        public static GeneralPayoutSummary from(GeneralPayout p) {
            return new GeneralPayoutSummary(p.getPayoutId(), p.getPayoutType(), p.getAmount(),
                    p.getStatus(), p.getRequestedOn(), p.getPaidOn(),
                    p.getPaidPremiumTotal(), p.getAppliedRate(),
                    p.getElapsedMonths(), p.getInstallmentCount());
        }
    }
}
