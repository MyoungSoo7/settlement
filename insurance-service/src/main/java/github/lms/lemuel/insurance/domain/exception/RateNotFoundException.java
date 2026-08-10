package github.lms.lemuel.insurance.domain.exception;

import github.lms.lemuel.insurance.domain.Gender;

/**
 * 적용 가능한 요율 부재 — 산출 불가.
 *
 * <p>폴백 요율로 메꾸지 않는다: 요율이 없으면 산출 자체를 거부한다
 * (ai-service 의 LLM 폴백 금지와 같은 축 — 임의 값이 금액 경로에 들어가는 것을 차단).
 */
public class RateNotFoundException extends RuntimeException {

    public RateNotFoundException(String productCode, Gender gender, int insuranceAge, int paymentTermYears) {
        super("적용 가능한 요율이 없습니다: product=" + productCode + ", gender=" + gender
                + ", insuranceAge=" + insuranceAge + ", paymentTermYears=" + paymentTermYears);
    }
}
