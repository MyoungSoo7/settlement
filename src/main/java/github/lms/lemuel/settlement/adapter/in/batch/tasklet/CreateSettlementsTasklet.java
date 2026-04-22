package github.lms.lemuel.settlement.adapter.in.batch.tasklet;

import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementCommand;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 정산 생성 Tasklet
 * UseCase만 호출하는 얇은 어댑터
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateSettlementsTasklet implements Tasklet {

    private final CreateDailySettlementsUseCase createDailySettlementsUseCase;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate targetDate = LocalDate.now().minusDays(1); // 전일 정산

        log.info("정산 생성 Tasklet 시작: targetDate={}", targetDate);

        CreateSettlementCommand command = new CreateSettlementCommand(targetDate);
        CreateSettlementResult result = createDailySettlementsUseCase.createDailySettlements(command);

        log.info("정산 생성 Tasklet 완료: createdCount={}, totalPayments={}",
                result.createdCount(), result.totalPayments());

        // Step 메타데이터에 결과 기록
        // StepContribution API: incrementReadCount/incrementWriteCount는 인자 없이 1씩 증가
        // 대신 StepExecution에 직접 설정하거나 로그로만 기록
        contribution.getStepExecution().setReadCount(result.totalPayments());
        contribution.getStepExecution().setWriteCount(result.createdCount());

        return RepeatStatus.FINISHED;
    }
}
