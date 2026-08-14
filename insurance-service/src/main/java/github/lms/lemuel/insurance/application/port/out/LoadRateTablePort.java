package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.Gender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 요율 조회 포트 — (상품, 성별, 보험나이, 납입기간, 기준일)로 적용 요율 하나를 찾는다.
 *
 * <p>D-P1: effective_from 버저닝 — 기준일 이전에 개시된 요율 중 최신 행이 이긴다.
 * 요율이 없으면 빈 Optional — 폴백 없이 산출을 거부하는 것은 호출측 몫.
 */
public interface LoadRateTablePort {

    Optional<RateSnapshot> findApplicableRate(String productCode, Gender gender,
                                              int insuranceAge, int paymentTermYears, LocalDate asOf);

    record RateSnapshot(long rateTableId, BigDecimal ratePerMille) {
    }
}
