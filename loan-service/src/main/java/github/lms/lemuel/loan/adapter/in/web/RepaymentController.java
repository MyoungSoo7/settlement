package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.loan.adapter.in.web.dto.RepaymentScheduleResponse;
import github.lms.lemuel.loan.adapter.in.web.dto.RepaymentSimulateRequest;
import github.lms.lemuel.loan.application.port.in.SimulateRepaymentUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대출 상환 스케줄 시뮬레이션 API — 원금·기간·이자율·상환방식으로 회차별 상환표를 미리 계산한다.
 * 대출 생성/영속화와 무관한 순수 미리보기(부수효과 없음).
 */
@RestController
@RequestMapping("/loans/repayment")
public class RepaymentController {

    private final SimulateRepaymentUseCase simulateRepaymentUseCase;

    public RepaymentController(SimulateRepaymentUseCase simulateRepaymentUseCase) {
        this.simulateRepaymentUseCase = simulateRepaymentUseCase;
    }

    @PostMapping("/simulate")
    public ResponseEntity<RepaymentScheduleResponse> simulate(@Valid @RequestBody RepaymentSimulateRequest req) {
        return ResponseEntity.ok(RepaymentScheduleResponse.from(
                simulateRepaymentUseCase.simulate(req.toCommand())));
    }
}
