package github.lms.lemuel.insurance.adapter.in.schedule;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.MonitorBancaConcentrationUseCase;
import github.lms.lemuel.insurance.application.port.in.MonitorBancaConcentrationUseCase.BancaMonitorResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;

/**
 * 방카슈랑스 25%룰 모니터링 배치 — 매월 당해연도 누적 비중을 점검한다.
 *
 * <p>규제 위반은 조기 탐지가 핵심이다 — 연말 정산 시점에 발견하면 되돌릴 수 없다.
 */
@Component
public class BancaConcentrationScheduler {

    private static final Logger log = LoggerFactory.getLogger(BancaConcentrationScheduler.class);

    private final MonitorBancaConcentrationUseCase useCase;
    /** KST 기준 시각 소스 — 연도 경계(1월 1일 자정)에서 UTC 면 전년도를 재점검하게 된다. */
    private final Clock clock;
    /** 규제 준수 점검 실행 이력 — 잡 단위 감사 1건 (위반 0건이어도 "점검했음"이 증빙). */
    private final AuditLogger auditLogger;

    public BancaConcentrationScheduler(
            MonitorBancaConcentrationUseCase useCase, Clock clock, AuditLogger auditLogger) {
        this.useCase = useCase;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    /** 매월 2일 06:00 (KST) — 월 마감(1일 05:00) 이후 당해연도 누적 점검. */
    @Scheduled(cron = "${app.insurance.batch.banca-rule-cron:0 0 6 2 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "insurance-banca-rule-monitor", lockAtMostFor = "PT30M")
    public void checkCurrentYear() {
        Year year = Year.from(LocalDate.now(clock));
        log.info("[BancaRule] 시작: year={}", year);
        BancaMonitorResult result = useCase.checkYear(year);
        log.info("[BancaRule] 완료: 집계조합={} 위반={}",
                result.aggregatedPairs(), result.violations().size());

        auditLogger.record(AuditAction.INSURANCE_BANCA_RULE_CHECKED,
                "BancaRuleMonitorJob", year.toString(),
                String.format("{\"year\":\"%s\",\"aggregatedPairs\":%d,\"violations\":%d}",
                        year, result.aggregatedPairs(), result.violations().size()));
    }
}
