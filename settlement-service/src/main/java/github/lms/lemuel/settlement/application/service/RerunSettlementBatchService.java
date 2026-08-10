package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import github.lms.lemuel.settlement.application.port.in.RerunSettlementBatchUseCase;
import github.lms.lemuel.settlement.application.port.out.RunSettlementConfirmBatchPort;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunReport;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunRequest;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 정산 배치 재실행 오케스트레이터.
 *
 * <p><b>부분 실패 격리</b>: 각 단계를 독립적으로 실행하고 예외를 리포트에 흡수한다 — 앞 단계가
 * 터졌다고 뒤 단계를 못 돌리면 운영자는 결국 DB 를 직접 만지게 된다. 대신 실패를 삼키지는
 * 않는다: 리포트의 {@code complete=false} 와 {@code failedSteps} 로 그대로 드러난다.
 *
 * <p><b>사전 검증 우선</b>: 일자 게이트({@link SettlementRerunRequest})를 단계 실행 <b>전에</b>
 * 통과시킨다 — 미래/과도 소급 일자로 첫 단계가 이미 실행된 뒤 거부되면 의미가 없다.
 */
@Slf4j
@Service
public class RerunSettlementBatchService implements RerunSettlementBatchUseCase {

    private final RunSettlementConfirmBatchPort confirmBatchPort;
    private final ReleaseHoldbackUseCase releaseHoldbackUseCase;
    private final ExecutePayoutUseCase executePayoutUseCase;
    private final Clock clock;
    private final int maxLookbackDays;

    public RerunSettlementBatchService(RunSettlementConfirmBatchPort confirmBatchPort,
                                       ReleaseHoldbackUseCase releaseHoldbackUseCase,
                                       ExecutePayoutUseCase executePayoutUseCase,
                                       Clock clock,
                                       @Value("${app.settlement.rerun.max-lookback-days:90}") int maxLookbackDays) {
        this.confirmBatchPort = confirmBatchPort;
        this.releaseHoldbackUseCase = releaseHoldbackUseCase;
        this.executePayoutUseCase = executePayoutUseCase;
        this.clock = clock;
        this.maxLookbackDays = maxLookbackDays;
    }

    @Override
    public SettlementRerunReport rerun(SettlementRerunScope scope, LocalDate targetDate) {
        LocalDate today = LocalDate.now(clock);
        // 미지정이면 어제 — 스케줄러(SettlementScheduler)의 기본 대상과 같은 날짜를 가리키게 한다.
        LocalDate resolved = targetDate != null ? targetDate : today.minusDays(1);

        // 도메인 게이트 — 통과하지 못하면 어떤 단계도 실행되지 않는다.
        SettlementRerunRequest request =
                SettlementRerunRequest.of(scope, resolved, today, maxLookbackDays);

        List<SettlementRerunReport.StepResult> results = new ArrayList<>();
        for (SettlementRerunScope step : request.steps()) {
            results.add(runStep(step, request.targetDate()));
        }
        SettlementRerunReport report = new SettlementRerunReport(request.targetDate(), results);

        if (report.complete()) {
            log.info("정산 배치 재실행 완료: scope={}, targetDate={}, affected={}",
                    scope, request.targetDate(), report.totalAffected());
        } else {
            log.warn("정산 배치 재실행 부분 실패: scope={}, targetDate={}, failedSteps={}",
                    scope, request.targetDate(), report.failedSteps());
        }
        return report;
    }

    /** 단계 하나를 실행하고 결과를 값으로 반환한다 — 예외는 여기서 실패 결과로 변환된다. */
    private SettlementRerunReport.StepResult runStep(SettlementRerunScope step, LocalDate targetDate) {
        try {
            return switch (step) {
                case CONFIRM -> runConfirm(targetDate);
                case HOLDBACK_RELEASE -> runHoldbackRelease(targetDate);
                case PAYOUT_EXECUTE -> runPayoutExecute();
                // ALL 은 expand() 에서 전개되므로 단계로 도달하지 않는다.
                case ALL -> SettlementRerunReport.StepResult.failed(step, "전개되지 않은 ALL 단계");
            };
        } catch (RuntimeException e) {
            log.error("정산 배치 재실행 단계 실패: step={}, targetDate={}", step, targetDate, e);
            return SettlementRerunReport.StepResult.failed(step, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private SettlementRerunReport.StepResult runConfirm(LocalDate targetDate) {
        RunSettlementConfirmBatchPort.BatchRunResult result = confirmBatchPort.runFor(targetDate);
        String detail = "status=" + result.status() + ", read=" + result.read() + ", confirmed=" + result.written();
        // COMPLETED 가 아니면 성공으로 집계하지 않는다 — Batch 가 FAILED 로 끝났는데 200 OK 가
        // 나가면 운영자는 복구된 줄 알고 손을 뗀다.
        return result.isCompleted()
                ? SettlementRerunReport.StepResult.succeeded(SettlementRerunScope.CONFIRM, result.written(), detail)
                : SettlementRerunReport.StepResult.failed(SettlementRerunScope.CONFIRM, detail);
    }

    private SettlementRerunReport.StepResult runHoldbackRelease(LocalDate targetDate) {
        int released = releaseHoldbackUseCase.releaseAllDueOn(targetDate);
        return SettlementRerunReport.StepResult.succeeded(
                SettlementRerunScope.HOLDBACK_RELEASE, released, "released=" + released);
    }

    private SettlementRerunReport.StepResult runPayoutExecute() {
        ExecutePayoutUseCase.ExecutionReport result = executePayoutUseCase.executeAllPending();
        String detail = "succeeded=" + result.succeeded()
                + ", failed=" + result.failed()
                + ", limitedSkipped=" + result.limitedSkipped();
        // 개별 송금 실패는 단계 실패가 아니다 — 한도 초과/반송은 다음 영업일 재시도가 정상 경로이고,
        // 건수는 detail 로 그대로 노출된다.
        return SettlementRerunReport.StepResult.succeeded(
                SettlementRerunScope.PAYOUT_EXECUTE, result.succeeded(), detail);
    }
}
