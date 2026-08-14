package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.SellerTier;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 요율 해석 미리보기 (ADR 0032 §6).
 *
 * <p>"이 셀러에게 이 날짜에 어떤 요율이 왜 적용되는가"를 정산을 만들지 않고 확인한다.
 * 정책 등록 후 의도대로 해석되는지 확인하는 것이 목적이라, 실제 적용과 <b>같은 해석 경로</b>를 쓴다.
 */
public interface SimulateCommissionRateUseCase {

    RateSimulation simulate(Long sellerId, SellerTier tier, LocalDate at);

    /** @param source 근거 표기 — SELLER:{id} | TIER:{등급} | DEFAULT_TIER */
    record RateSimulation(Long sellerId, String tier, LocalDate at, BigDecimal rate, String source) { }
}
