package github.lms.lemuel.pgreconciliation.domain;

import github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException;

import java.math.BigDecimal;

/**
 * PG 정산파일이 매출에서 공제하는 항목들의 분해.
 *
 * <p><b>왜 분해하는가</b>: PG 는 수수료를 단일 금액으로 주지 않는다. 기본 수수료와 그 부가세,
 * 에스크로 수수료와 부가세, 이체 수수료가 각각 따로 찍히고 이 합이 매출에서 빠져 실입금이 된다.
 * 단일 {@code fee} 로 뭉개면 "왜 100원이 덜 들어왔는지"를 사후에 추적할 수 없고, 실무 정산 사고의
 * 상당수가 바로 이 수수료·부가세 오차에서 난다.
 *
 * <p><b>부호 규약</b>: 모든 항목은 <b>양수</b>다. 공제라는 사실은 이 타입 자체가 표현하고,
 * 차감 부호는 사용처({@link PgTransactionRow#expectedNetDeposit()})가 정한다. 음수 공제를 허용하면
 * "환급"과 "공제"가 한 필드에 섞여 합계의 의미가 무너진다.
 *
 * <p>합산은 <b>항목별 계산 후 합산</b>이다 — 총액에 요율을 곱하는 방식은 라운딩 때문에 PG 신고값과
 * 어긋난다.
 */
public record PgFeeBreakdown(
        BigDecimal pgFee,         // PG 기본 수수료
        BigDecimal pgFeeVat,      // PG 기본 수수료의 부가세
        BigDecimal escrowFee,     // 에스크로 수수료
        BigDecimal escrowVat,     // 에스크로 수수료의 부가세
        BigDecimal transferFee,   // 이체(송금) 수수료
        BigDecimal transferVat,   // 이체 수수료의 부가세
        BigDecimal additionalFee  // 기타 부가 수수료 (PG 별 특약 등)
) {

    /** null 항목을 0 으로 흡수하고 음수를 거부한다 — 생성된 인스턴스는 항상 합산 가능한 상태다. */
    public PgFeeBreakdown {
        pgFee = requireNonNegative(pgFee, "pgFee");
        pgFeeVat = requireNonNegative(pgFeeVat, "pgFeeVat");
        escrowFee = requireNonNegative(escrowFee, "escrowFee");
        escrowVat = requireNonNegative(escrowVat, "escrowVat");
        transferFee = requireNonNegative(transferFee, "transferFee");
        transferVat = requireNonNegative(transferVat, "transferVat");
        additionalFee = requireNonNegative(additionalFee, "additionalFee");
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw new PgReconciliationInvariantViolationException(
                    field + " 은 음수일 수 없습니다 (공제는 양수로 표현): " + value);
        }
        return value;
    }

    public static PgFeeBreakdown of(BigDecimal pgFee, BigDecimal pgFeeVat,
                                    BigDecimal escrowFee, BigDecimal escrowVat,
                                    BigDecimal transferFee, BigDecimal transferVat,
                                    BigDecimal additionalFee) {
        return new PgFeeBreakdown(pgFee, pgFeeVat, escrowFee, escrowVat,
                transferFee, transferVat, additionalFee);
    }

    /** 공제 없음 — 전 항목 0. */
    public static PgFeeBreakdown none() {
        return new PgFeeBreakdown(null, null, null, null, null, null, null);
    }

    /**
     * 분해 이전 형식(단일 수수료 컬럼)의 하위호환 매핑 — 기본 수수료로 취급한다.
     *
     * <p>이렇게 만든 값은 {@link #isDecomposed()} 가 false 라, 실입금 검증에서 "분해 정보가 없어
     * 검증 불가"와 "분해했는데 안 맞음"을 구분할 수 있다.
     */
    public static PgFeeBreakdown legacy(BigDecimal fee) {
        return new PgFeeBreakdown(fee, null, null, null, null, null, null);
    }

    /** 매출에서 빠지는 총 공제액 — 항목별 합산. */
    public BigDecimal totalDeduction() {
        return pgFee
                .add(pgFeeVat)
                .add(escrowFee)
                .add(escrowVat)
                .add(transferFee)
                .add(transferVat)
                .add(additionalFee);
    }

    /** 부가세 항목 합계 — 세무 대사에서 매입세액 근거로 쓰인다. */
    public BigDecimal totalVat() {
        return pgFeeVat.add(escrowVat).add(transferVat);
    }

    /**
     * 기본 수수료 외 항목이 하나라도 있는가 — 즉 PG 가 분해된 정보를 준 파일인가.
     *
     * <p>false 면 단일 수수료만 아는 상태이므로, 실입금 불일치를 PG 오류로 단정할 수 없다.
     */
    public boolean isDecomposed() {
        return pgFeeVat.signum() != 0
                || escrowFee.signum() != 0
                || escrowVat.signum() != 0
                || transferFee.signum() != 0
                || transferVat.signum() != 0
                || additionalFee.signum() != 0;
    }
}
