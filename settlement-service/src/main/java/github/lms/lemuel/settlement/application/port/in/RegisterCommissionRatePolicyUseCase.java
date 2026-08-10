package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.CommissionRatePolicy;
import github.lms.lemuel.settlement.domain.RateScope;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 요율 정책 등록 (ADR 0032). */
public interface RegisterCommissionRatePolicyUseCase {

    CommissionRatePolicy register(RegisterPolicyCommand command, LocalDate today);

    /**
     * @param reason 왜 이 요율인가 — 감사 없이 요율이 바뀌지 않게 필수
     */
    record RegisterPolicyCommand(RateScope scope, String scopeKey, BigDecimal rate,
                                 LocalDate effectiveFrom, LocalDate effectiveTo,
                                 String reason, String createdBy) { }
}
