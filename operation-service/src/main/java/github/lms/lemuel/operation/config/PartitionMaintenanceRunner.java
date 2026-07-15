package github.lms.lemuel.operation.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 + 매월 1회 월별 파티션 런웨이를 굴린다 — operation-service(opslab).
 *
 * <p>두 개의 파티션드 테이블을 유지한다: {@code audit_logs}(ensure_audit_log_partition) 와
 * {@code ops_metric_bucket}(ensure_ops_metric_bucket_partition). 월별 RANGE 파티션은 유한 구간(2028-12)까지만
 * 선생성돼 그 이후 삽입은 DEFAULT 파티션으로 흘러들어 프루닝·리텐션 이점이 사라진다. 이 러너가 부팅 때 두
 * 함수를 호출해 미래 파티션을 굴린다(재배포·재기동 주기로 런웨이가 계속 갱신됨).
 *
 * <p><b>prune(파기)은 호출하지 않는다</b> — 삭제는 운영 판단. 선생성만 자동화.
 * <b>fail-open:</b> 테스트 DB(create-drop)엔 함수가 없어 실패하므로 각 호출을 독립적으로 예외를 삼켜(warn)
 * 부팅을 막지 않는다(한 함수 실패가 다른 함수 호출을 막지 않도록 개별 try-catch).
 */
@Component
public class PartitionMaintenanceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final String schema;
    private final int monthsAhead;

    public PartitionMaintenanceRunner(
            JdbcTemplate jdbcTemplate,
            // 네이티브 SQL 은 hibernate.default_schema 를 무시하므로 함수명을 명시 한정한다(operation=opslab).
            @Value("${spring.jpa.properties.hibernate.default_schema:public}") String schema,
            @Value("${app.partition.months-ahead:3}") int monthsAhead) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
        this.monthsAhead = monthsAhead;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensurePartitions();
    }

    /**
     * 매월 1일 02:30 — 미래 파티션 롤(부팅 ApplicationRunner + 월간 스케줄 병행).
     *
     * <p>무재배포 장기 가동 시 부팅 1회만으로는 런웨이가 갱신되지 않아 파티션이 소진된다
     * (order/settlement 의 월간 @Scheduled 와 비대칭이던 갭). 단일 인스턴스 위성 서비스라 노드 경합이
     * 없어 ShedLock 없이 안전하다(다중 replicas 로 확장 시 settlement 처럼 @SchedulerLock 도입 필요).
     */
    @Scheduled(cron = "${app.partition.ensure-cron:0 30 2 1 * *}", zone = "Asia/Seoul")
    public void ensureMonthly() {
        ensurePartitions();
    }

    private void ensurePartitions() {
        ensure("ensure_audit_log_partition", monthsAhead);
        ensure("ensure_ops_metric_bucket_partition", monthsAhead);
    }

    private void ensure(String function, int horizon) {
        try {
            Integer created = jdbcTemplate.queryForObject(
                    "SELECT " + schema + "." + function + "(?)", Integer.class, horizon);
            log.info("[PartitionMaintenance] {}({}) 완료: 신규 파티션 {}개", function, horizon, created);
        } catch (RuntimeException e) {
            log.warn("[PartitionMaintenance] {} 실패 — 유지보수 스킵 (fail-open): {}", function, e.getMessage());
        }
    }
}
