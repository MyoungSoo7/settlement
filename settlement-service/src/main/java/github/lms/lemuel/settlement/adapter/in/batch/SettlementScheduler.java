package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 정산 확정 스케줄러 — 매일 새벽 만기 정산을 확정하는 Spring Batch Job 을 실행한다.
 *
 * <p>정산 <b>생성</b>은 이 스케줄러가 담당하지 않는다. 결제 완료(payment.captured) 이벤트를
 * {@code PaymentEventKafkaConsumer} 가 수신해 셀러 등급·정산주기·holdback 을 적용한 정산을 실시간
 * 생성하는 이벤트 드리븐 경로가 단일 생성 경로다.
 *
 * <p>확정은 {@code SettlementConfirmJobConfig} 의 청크 Job 을 {@link JobOperator} 로 동기 실행한다
 * (Spring Batch 6 에서 JobOperator 가 JobLauncher 를 대체). ShedLock 으로 replicas 중 1 개만
 * 트리거하고, 실행이 동기라 락은 Job 종료까지 유지된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

    private final JobOperator jobOperator;

    /** 빈 이름이 곧 {@code SettlementConfirmJobConfig.JOB_NAME}("confirmSettlementJob") — 유일 Job 빈. */
    private final Job confirmSettlementJob;

    /** KST 기준 시각 소스 — 전일(targetDate) 계산이 JVM 기본 타임존에 흔들리지 않게 한다. */
    private final Clock clock;

    /** 확정 배치 실행을 audit_logs 에 잡 요약 1건으로 남긴다 (건별이 아니라 잡 단위 — 확정은 대량 일괄). */
    private final AuditLogger auditLogger;

    /**
     * 매일 새벽 3시(KST)에 전일(만기) 정산 확정 Job 실행.
     *
     * <p>cron zone 과 본문의 {@code now(clock)} 모두 KST 로 고정한다 — UTC JVM 에서 zone 미지정 시
     * 트리거 시각과 targetDate 가 하루 어긋나 엉뚱한 날짜를 확정할 수 있다.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-confirm-daily", lockAtMostFor = "PT30M")
    public void scheduledConfirmDailySettlements() throws Exception {
        LocalDate targetDate = LocalDate.now(clock).minusDays(1);

        // requestedAt 으로 매 실행을 고유 JobInstance 로 만든다(동일 일자 재트리거 허용).
        // 멱등성은 리더가 REQUESTED 만 조회하므로 데이터 수준에서 보장된다.
        JobParameters params = new JobParametersBuilder()
                .addString("targetDate", targetDate.toString())
                .addLong("requestedAt", System.currentTimeMillis())
                .toJobParameters();

        log.info("정산 확정 Job 시작: targetDate={}", targetDate);
        JobExecution execution = jobOperator.start(confirmSettlementJob, params);

        long read = execution.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
        long write = execution.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
        log.info("정산 확정 Job 종료: status={}, read={}, write={}", execution.getStatus(), read, write);

        // 정산 확정은 실질적 금전 확정(payout·원장 트리거) — 잡 단위 감사 기록으로 "확정 배치가 언제 몇 건을
        // 확정했는지" 추적 가능하게 한다. 컨슈머/배치 경로라 actor 는 system. 조립 실패해도 AuditLogger 가 삼킨다.
        auditLogger.record(AuditAction.SETTLEMENT_CONFIRMED, "SettlementConfirmJob", targetDate.toString(),
                String.format("{\"targetDate\":\"%s\",\"status\":\"%s\",\"read\":%d,\"confirmed\":%d}",
                        targetDate, execution.getStatus(), read, write));
    }
}
