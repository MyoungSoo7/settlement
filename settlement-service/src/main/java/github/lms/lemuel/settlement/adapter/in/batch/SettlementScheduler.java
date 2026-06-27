package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase.ConfirmSettlementCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 정산 확정 스케줄러 — 매일 새벽 만기 정산을 확정한다.
 *
 * <p>정산 <b>생성</b>은 이 스케줄러가 담당하지 않는다. 결제 완료(payment.captured) 이벤트를
 * {@code PaymentEventKafkaConsumer} 가 수신해 셀러 등급·정산주기·holdback 을 적용한 정산을 실시간
 * 생성하는 이벤트 드리븐 경로가 단일 생성 경로다. 과거의 일일 생성 배치
 * ({@code CreateDailySettlementsService})는 등급/holdback 미적용 + 이벤트 경로와 중복·충돌하는
 * 레거시라 제거됐다.
 *
 * <p>UseCase 만 호출하는 얇은 어댑터.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

    private final ConfirmDailySettlementsUseCase confirmDailySettlementsUseCase;

    /**
     * 매일 새벽 3시에 전일(만기) 정산 확정.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "settlement-confirm-daily", lockAtMostFor = "PT30M")
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
