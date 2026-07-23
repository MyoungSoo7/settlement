package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 세무 전용 라운딩 정책 — <b>원단위 절사</b>(1원 미만 버림).
 *
 * <p>공용 {@code Money} VO 의 통화 라운딩(scale 2, {@link RoundingMode#HALF_UP})과 <b>의도적으로 분리</b>한다.
 * 세무 산출물(부가세·원천징수)은 관례상 원 단위로 절사하므로, {@code Money} 를 통과시키면 scale 2 HALF_UP
 * 반올림이 먼저 개입해 세무 절사 의미가 손상된다. 따라서 세무 금액은 곱셈 원값에 이 정책을 직접 적용한다.
 *
 * <p>절사는 {@link RoundingMode#DOWN}(0 방향 버림, scale 0)로 강제한다. 세무 금액은 음수가 아니므로
 * DOWN 은 곧 floor 와 동치다.
 */
public final class TaxRounding {

    private TaxRounding() {
    }

    /**
     * 원단위 절사 — {@code amount} 의 소수부를 버려 정수 원(scale 0)으로 만든다.
     *
     * @throws TaxInvariantViolationException {@code amount} 가 null 이거나 음수인 경우
     */
    public static BigDecimal floorToWon(BigDecimal amount) {
        if (amount == null) {
            throw new TaxInvariantViolationException("세무 라운딩 대상 금액은 필수입니다");
        }
        if (amount.signum() < 0) {
            throw new TaxInvariantViolationException("세무 금액은 음수일 수 없습니다: " + amount.toPlainString());
        }
        return amount.setScale(0, RoundingMode.DOWN);
    }
}
