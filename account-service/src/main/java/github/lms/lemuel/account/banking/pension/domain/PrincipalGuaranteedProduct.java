package github.lms.lemuel.account.banking.pension.domain;

import github.lms.lemuel.account.banking.pension.domain.exception.InvalidInvestmentInstructionException;
import github.lms.lemuel.account.banking.pension.domain.exception.InvalidPensionRateException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 운용지시(최소 모델) — <b>원리금보장 상품 하나</b>와 그 표면이율.
 *
 * <p>실제 퇴직연금 운용지시는 상품 N 개에 비중을 배분하고 합계 100% 를 맞추는 모델이지만,
 * 이 서브도메인은 의도적으로 그 앞단에서 멈춘다. 비중 배분을 도입하는 순간 재배분·리밸런싱·
 * 실적배당 평가손익이 따라 들어오는데, 그건 이 계정계의 관심사(적립금 부채 집계)가 아니다.
 * 그래서 "무엇 하나에 넣어뒀는가"만 값으로 들고 있는다.
 *
 * <p>이율은 저장 컬럼(numeric 9,6)에 맞춰 소수점 6자리 HALF_UP 으로 정규화한 뒤 {@code [0,1)} 을 검증한다
 * — 정규화 후에 검증해야 0.9999999 → 1.000000 같은 상한 탈출을 잡는다.
 */
public record PrincipalGuaranteedProduct(String productName, BigDecimal rate) {

    /** 이율 컬럼 스케일 — {@code numeric(9,6)} 과 동일하게 맞춘다. */
    public static final int RATE_SCALE = 6;

    public PrincipalGuaranteedProduct {
        if (productName == null || productName.isBlank()) {
            throw new InvalidInvestmentInstructionException(productName);
        }
        productName = productName.trim();
        rate = normalizeRate(rate);
    }

    /**
     * 이율을 저장 스케일로 정규화하고 {@code [0,1)} 을 강제한다.
     * 퇴직연금 애그리게이트의 {@code annualRate} 도 같은 규칙을 쓰므로 여기 하나로 모아 둔다.
     */
    public static BigDecimal normalizeRate(BigDecimal rate) {
        if (rate == null) {
            throw new InvalidPensionRateException(null);
        }
        BigDecimal normalized = rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        if (normalized.signum() < 0 || normalized.compareTo(BigDecimal.ONE) >= 0) {
            throw new InvalidPensionRateException(rate);
        }
        return normalized;
    }
}
