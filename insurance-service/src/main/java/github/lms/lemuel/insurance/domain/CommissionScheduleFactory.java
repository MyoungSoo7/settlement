package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidCommissionScheduleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 수수료 선지급 스케줄 팩토리 — 초년도 총액을 정확히 12회로 분할.
 *
 * <p><b>D4 — 선지급 12회는 12행이다</b>:
 * 초년도 총액 P를 정확히 12개 회차(installment)로 분할하고,
 * 12회 합계가 P와 1원 오차 없이 정확히 일치하도록 보장한다.
 *
 * <p><b>분할 알고리즘</b>:
 * <ul>
 *   <li>기본 몫: baseAmount = P / 12, 통화 최소단위 절사</li>
 *   <li>나머지: remainder = P - (baseAmount × 12) — 절사로 버려진 잔액</li>
 *   <li>1~11회: baseAmount / 12회: baseAmount + remainder</li>
 * </ul>
 *
 * <p>예시: P = 100,000원 (100000 / 12 = 8333.3333... → 절사 8333.33)
 * <ul>
 *   <li>나머지: 100,000 - (8,333.33 × 12) = 0.04</li>
 *   <li>1~11회: 8,333.33원</li>
 *   <li>12회: 8,333.33 + 0.04 = 8,333.37원</li>
 *   <li>합계: 8,333.33 × 11 + 8,333.37 = 100,000.00 ✓</li>
 * </ul>
 *
 * @see CommissionSchedule 각 회차
 * @see CommissionConstants#CLAWBACK_WINDOW_MONTHS D6 환수 창구
 */
public final class CommissionScheduleFactory {

    private CommissionScheduleFactory() {
        // factory utility
    }

    /**
     * 초년도 수수료 선지급 스케줄 생성.
     *
     * <p>D4: 정확히 12개 회차로 분할.
     * D5: recipientType = 'FC' (계층 수수료 계산은 미구현).
     *
     * @param policyId           대상 계약 UUID (String)
     * @param fcId               설계사 식별자
     * @param firstYearTotal     초년도 총액 (>0, BigDecimal)
     * @param rate               수수료율 (정보성, 스케줄에 기록만 함)
     * @return 12개 CommissionSchedule 리스트
     * @throws InvalidCommissionScheduleException 입력 null 또는 음수/0 시
     */
    public static List<CommissionSchedule> createFirstYearSchedule(
            String policyId,
            String fcId,
            BigDecimal firstYearTotal,
            BigDecimal rate
    ) {
        // Validation — null check
        if (policyId == null) {
            throw new InvalidCommissionScheduleException("policyId 는 null 일 수 없습니다");
        }
        if (fcId == null) {
            throw new InvalidCommissionScheduleException("fcId 는 null 일 수 없습니다");
        }
        if (firstYearTotal == null) {
            throw new InvalidCommissionScheduleException("firstYearTotal 은 null 일 수 없습니다");
        }
        if (rate == null) {
            throw new InvalidCommissionScheduleException("rate 는 null 일 수 없습니다");
        }

        // Validation — value check
        if (firstYearTotal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidCommissionScheduleException(
                    "firstYearTotal 은 0보다 커야 합니다: " + firstYearTotal
            );
        }

        // 통화 최소단위보다 잘게 쪼개진 총액은 거부한다.
        // 허용하면 나머지 가산으로 마지막 회차의 스케일이 AMOUNT_SCALE 를 넘고
        // (예: 100.005 → 12회차 8.375), V1 의 NUMERIC(19,2) 저장 시 반올림되어
        // "12행 합계 == P" 불변식이 DB 에서 깨진다.
        if (firstYearTotal.stripTrailingZeros().scale() > CommissionConstants.AMOUNT_SCALE) {
            throw new InvalidCommissionScheduleException(
                    "firstYearTotal 은 통화 최소단위(소수점 " + CommissionConstants.AMOUNT_SCALE
                            + "자리)보다 잘게 쪼갤 수 없습니다: " + firstYearTotal.toPlainString()
            );
        }

        // 12회로 쪼갰을 때 회차액이 0원이 되면 V1 의
        // chk_commission_installment_amount_positive (installment_amount > 0) 를 위반한다.
        // 최소 총액 = 12 × 통화 최소단위.
        BigDecimal minimumTotal = BigDecimal.ONE
                .movePointLeft(CommissionConstants.AMOUNT_SCALE)
                .multiply(BigDecimal.valueOf(CommissionConstants.INSTALLMENT_COUNT));
        if (firstYearTotal.compareTo(minimumTotal) < 0) {
            throw new InvalidCommissionScheduleException(
                    "firstYearTotal 은 회차당 최소 1단위가 되도록 " + minimumTotal
                            + " 이상이어야 합니다: " + firstYearTotal
            );
        }

        final int count = CommissionConstants.INSTALLMENT_COUNT;
        final BigDecimal countBd = BigDecimal.valueOf(count);

        // D6: 각 회차액 = 초년도총액 / 12, 통화 최소단위 절사
        BigDecimal baseAmount = firstYearTotal.divide(
                countBd,
                CommissionConstants.AMOUNT_SCALE,
                RoundingMode.DOWN
        );

        // 절사로 버려진 나머지 = P - (기본 몫 × 12).
        // D6: 이 나머지를 마지막 회차에 가산해 12회분 합계를 P 와 정확히 일치시킨다.
        BigDecimal remainder = firstYearTotal.subtract(baseAmount.multiply(countBd));
        BigDecimal lastAmount = baseAmount.add(remainder);

        List<CommissionSchedule> schedules = new ArrayList<>(count);
        for (int installmentNo = 1; installmentNo <= count; installmentNo++) {
            BigDecimal amount = (installmentNo == count) ? lastAmount : baseAmount;
            schedules.add(newInstallment(policyId, fcId, installmentNo, amount, firstYearTotal));
        }

        return schedules;
    }

    private static CommissionSchedule newInstallment(
            String policyId,
            String fcId,
            int installmentNo,
            BigDecimal installmentAmount,
            BigDecimal firstYearTotal
    ) {
        return CommissionSchedule.builder()
                .commissionId(UUID.randomUUID().toString())
                .policyId(policyId)
                .fcId(fcId)
                .recipientType(CommissionConstants.RECIPIENT_TYPE_FC)  // D5: 이번 범위는 FC 만
                .parentCommissionId(null)                              // D5: 계층 계산 미구현
                .installmentNo(installmentNo)
                .installmentAmount(installmentAmount)
                .firstYearTotal(firstYearTotal)
                .dueDate(null)                                         // 호출자가 설정
                .status("SCHEDULED")
                .build();
    }
}
