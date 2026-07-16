package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.SimulateRepaymentUseCase;
import github.lms.lemuel.loan.domain.RepaymentSchedule;
import org.springframework.stereotype.Service;

/**
 * 상환 스케줄 시뮬레이션. 입력 검증·산정은 도메인 {@link RepaymentSchedule} 가 단일 출처로 담당하며,
 * 이 서비스는 커맨드를 그대로 위임한다(무상태·부수효과 없음).
 */
@Service
public class SimulateRepaymentService implements SimulateRepaymentUseCase {

    @Override
    public RepaymentSchedule simulate(SimulateRepaymentCommand command) {
        return RepaymentSchedule.of(
                command.principal(),
                command.termMonths(),
                command.annualRatePercent(),
                command.method());
    }
}
