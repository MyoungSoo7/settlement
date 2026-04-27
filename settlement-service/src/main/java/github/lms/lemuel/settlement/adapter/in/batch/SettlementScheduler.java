package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase.ConfirmSettlementCommand;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Settlement Batch Scheduler
 * @Scheduled를 사용한 정기 실행 (Spring Batch Job과 별개)
 * UseCase만 호출하는 얇은 어댑터
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

    private final CreateDailySettlementsUseCase createDailySettlementsUseCase;
    private final ConfirmDailySettlementsUseCase confirmDailySettlementsUseCase;

    /**
     * 매일 새벽 2시에 전일 정산 생성
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledCreateDailySettlements() {
        try {
            LocalDate targetDate = LocalDate.now().minusDays(1);
            log.info("스케줄 실행: 정산 생성 시작 - targetDate={}", targetDate);

            CreateSettlementCommand command = new CreateSettlementCommand(targetDate);
            var result = createDailySettlementsUseCase.createDailySettlements(command);

            log.info("스케줄 완료: 정산 생성 - createdCount={}, totalPayments={}",
                    result.createdCount(), result.totalPayments());
        } catch (Exception e) {
            log.error("정산 생성 스케줄 실패", e);
            // 메트릭/알림 발행 가능
            throw e;
        }
    }

    /**
     * 매일 새벽 3시에 전일 정산 확정
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledConfirmDailySettlements() {
        try {
            LocalDate targetDate = LocalDate.now().minusDays(1);
            log.info("스케줄 실행: 정산 확정 시작 - targetDate={}", targetDate);

            ConfirmSettlementCommand command = new ConfirmSettlementCommand(targetDate);
            var result = confirmDailySettlementsUseCase.confirmDailySettlements(command);

            log.info("스케줄 완료: 정산 확정 - confirmedCount={}, totalSettlements={}",
                    result.confirmedCount(), result.totalSettlements());
        } catch (Exception e) {
            log.error("정산 확정 스케줄 실패", e);
            // 메트릭/알림 발행 가능
            throw e;
        }
    }
}
