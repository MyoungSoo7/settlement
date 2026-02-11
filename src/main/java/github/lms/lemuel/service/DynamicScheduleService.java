package github.lms.lemuel.service;

import github.lms.lemuel.domain.SettlementScheduleConfig;
import github.lms.lemuel.repository.SettlementScheduleConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 동적 스케줄 관리 서비스
 * DB 기반으로 Cron 스케줄을 동적으로 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicScheduleService {

    private final SettlementScheduleConfigRepository scheduleConfigRepository;
    private final TaskScheduler taskScheduler;
    private final JobLauncher jobLauncher;
    private final Job createSettlementJob;
    private final Job confirmSettlementJob;

    // 스케줄 작업을 관리하는 맵
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();

    /**
     * 애플리케이션 시작 시 DB에서 스케줄 로드
     */
    @PostConstruct
    public void initializeSchedules() {
        log.info("Initializing dynamic schedules from database");
        reloadSchedules();
    }

    /**
     * 5분마다 스케줄 재로드 (DB 변경 감지)
     */
    @Scheduled(fixedDelay = 300000) // 5분
    public void reloadSchedules() {
        List<SettlementScheduleConfig> configs = scheduleConfigRepository.findByEnabled(true);

        log.info("Reloading {} enabled schedules", configs.size());

        for (SettlementScheduleConfig config : configs) {
            updateSchedule(config);
        }
    }

    /**
     * 스케줄 업데이트 (기존 스케줄 취소 후 새로 등록)
     */
    public void updateSchedule(SettlementScheduleConfig config) {
        String taskKey = config.getConfigKey();

        // 기존 스케줄 취소
        if (scheduledTasks.containsKey(taskKey)) {
            scheduledTasks.get(taskKey).cancel(false);
            scheduledTasks.remove(taskKey);
            log.info("Cancelled existing schedule: {}", taskKey);
        }

        if (!config.getEnabled()) {
            log.info("Schedule disabled: {}", taskKey);
            return;
        }

        // 새 스케줄 등록
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeScheduledJob(config),
                    new CronTrigger(config.getCronExpression())
            );

            scheduledTasks.put(taskKey, future);
            log.info("Scheduled job: {} with cron: {}",
                    taskKey, config.getCronExpression());

        } catch (Exception e) {
            log.error("Failed to schedule job: {}, cron: {}",
                    taskKey, config.getCronExpression(), e);
        }
    }

    /**
     * 스케줄된 작업 실행
     */
    private void executeScheduledJob(SettlementScheduleConfig config) {
        log.info("Executing scheduled job: {} at {}",
                config.getConfigKey(), LocalDateTime.now());

        try {
            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .addString("configKey", config.getConfigKey())
                    .toJobParameters();

            switch (config.getConfigKey()) {
                case "SETTLEMENT_CREATE":
                    jobLauncher.run(createSettlementJob, jobParameters);
                    break;

                case "SETTLEMENT_CONFIRM":
                    jobLauncher.run(confirmSettlementJob, jobParameters);
                    break;

                case "ADJUSTMENT_CONFIRM":
                    // SettlementAdjustment 확정 로직 (기존 배치 서비스 호출)
                    log.info("Executing adjustment confirmation");
                    break;

                default:
                    log.warn("Unknown scheduled job: {}", config.getConfigKey());
            }

            log.info("Successfully executed scheduled job: {}", config.getConfigKey());

        } catch (Exception e) {
            log.error("Failed to execute scheduled job: {}", config.getConfigKey(), e);
        }
    }

    /**
     * 스케줄 수동 업데이트 (API로 호출 가능)
     */
    public void refreshSchedule(String configKey) {
        scheduleConfigRepository.findByConfigKey(configKey).ifPresent(this::updateSchedule);
    }

    /**
     * 모든 스케줄 강제 재로드
     */
    public void refreshAllSchedules() {
        reloadSchedules();
    }
}
