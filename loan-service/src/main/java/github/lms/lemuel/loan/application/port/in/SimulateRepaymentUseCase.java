package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.RepaymentSchedule;

import java.math.BigDecimal;

/**
 * 상환 스케줄 시뮬레이션 인바운드 포트. 원금·기간·이자율·상환방식으로 회차별 상환표를 산정한다(부수효과 없음 —
 * 대출 생성/영속화와 무관한 순수 미리보기).
 */
public interface SimulateRepaymentUseCase {

    RepaymentSchedule simulate(SimulateRepaymentCommand command);

    /**
     * @param principal         대출 원금(원)
     * @param termMonths        기간(개월)
     * @param annualRatePercent 연이자율(%)
     * @param method            상환방식
     */
    record SimulateRepaymentCommand(BigDecimal principal, int termMonths,
                                    BigDecimal annualRatePercent, RepaymentMethod method) {
    }
}
