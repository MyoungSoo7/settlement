package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;

/**
 * PG 대사 차이 → 셀러 회수(clawback)액 산정 규칙.
 *
 * <p>회수는 이미 지급했거나 지급 예정인 돈을 셀러에게서 도로 가져오는 방향이라, <b>회수하지 않는다가
 * 기본</b>이고 회수 대상은 아래에 명시적으로 열거된 경우뿐이다. 잘못 회수하면 셀러 손실이고 정정도 번거롭다.
 *
 * <p>과소 정산(셀러에게 더 줘야 하는 방향)은 이 정책의 대상이 아니다 — 회수와 지급은 승인 절차가 다르다.
 *
 * <p>도메인에 두는 이유: 승인 미리보기와 실제 적용(Kafka 컨슈머)이 <b>같은 규칙</b>을 봐야
 * "미리보기엔 300원이라더니 실제로는 다르게 회수됐다"가 생기지 않는다.
 */
public final class ClawbackPolicy {

    private ClawbackPolicy() {
    }

    /**
     * @param difference {@code pgAmount - internalAmount} (부호 있음)
     * @return 회수액(양수). 회수 대상이 아니면 {@code null}
     */
    public static BigDecimal computeFor(String discrepancyType, BigDecimal internalAmount, BigDecimal difference) {
        if (discrepancyType == null) {
            return null;
        }
        return switch (discrepancyType) {
            // difference < 0 이면 PG 가 내부보다 적게 보냈다 → 셀러 과다 정산 → 그 차액 회수.
            case "AMOUNT_MISMATCH" ->
                    (difference != null && difference.signum() < 0) ? difference.abs() : null;
            // 내부에만 존재(PG 미송금) → 내부 금액 전액 회수.
            case "MISSING_PG" ->
                    (internalAmount != null && internalAmount.signum() > 0) ? internalAmount : null;
            // MISSING_INTERNAL / DUPLICATE / ROUNDING_DIFF / 미상 → 회수 없음.
            // FEE_MISMATCH 는 PG 측 수수료 오차라, 셀러 정산을 깎으면 손실을 애먼 쪽에 전가한다.
            default -> null;
        };
    }
}
