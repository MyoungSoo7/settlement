package github.lms.lemuel.controller;

import github.lms.lemuel.domain.SettlementScheduleConfig;
import github.lms.lemuel.repository.SettlementScheduleConfigRepository;
import github.lms.lemuel.service.DynamicScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 정산 스케줄 관리 API
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/schedules")
@RequiredArgsConstructor
public class SettlementScheduleController {

    private final SettlementScheduleConfigRepository scheduleConfigRepository;
    private final DynamicScheduleService dynamicScheduleService;

    /**
     * 모든 스케줄 조회
     */
    @GetMapping
    public ResponseEntity<List<SettlementScheduleConfig>> getAllSchedules() {
        return ResponseEntity.ok(scheduleConfigRepository.findAll());
    }

    /**
     * 특정 스케줄 조회
     */
    @GetMapping("/{configKey}")
    public ResponseEntity<SettlementScheduleConfig> getSchedule(@PathVariable String configKey) {
        return scheduleConfigRepository.findByConfigKey(configKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 스케줄 설정 수정
     */
    @PutMapping("/{id}")
    public ResponseEntity<SettlementScheduleConfig> updateSchedule(
            @PathVariable Long id,
            @RequestBody SettlementScheduleConfig request) {

        return scheduleConfigRepository.findById(id)
                .map(config -> {
                    config.setCronExpression(request.getCronExpression());
                    config.setEnabled(request.getEnabled());
                    config.setDescription(request.getDescription());

                    SettlementScheduleConfig saved = scheduleConfigRepository.save(config);

                    // 스케줄 즉시 반영
                    dynamicScheduleService.updateSchedule(saved);

                    log.info("Updated schedule: {} with cron: {}",
                            saved.getConfigKey(), saved.getCronExpression());

                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 스케줄 활성화/비활성화
     */
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<SettlementScheduleConfig> toggleSchedule(@PathVariable Long id) {
        return scheduleConfigRepository.findById(id)
                .map(config -> {
                    config.setEnabled(!config.getEnabled());
                    SettlementScheduleConfig saved = scheduleConfigRepository.save(config);

                    // 스케줄 즉시 반영
                    dynamicScheduleService.updateSchedule(saved);

                    log.info("Toggled schedule: {} to {}",
                            saved.getConfigKey(), saved.getEnabled());

                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 모든 스케줄 강제 재로드
     */
    @PostMapping("/reload")
    public ResponseEntity<String> reloadAllSchedules() {
        dynamicScheduleService.refreshAllSchedules();
        log.info("Manually reloaded all schedules");
        return ResponseEntity.ok("All schedules reloaded successfully");
    }

    /**
     * 특정 스케줄 강제 재로드
     */
    @PostMapping("/{configKey}/reload")
    public ResponseEntity<String> reloadSchedule(@PathVariable String configKey) {
        dynamicScheduleService.refreshSchedule(configKey);
        log.info("Manually reloaded schedule: {}", configKey);
        return ResponseEntity.ok("Schedule reloaded: " + configKey);
    }
}
