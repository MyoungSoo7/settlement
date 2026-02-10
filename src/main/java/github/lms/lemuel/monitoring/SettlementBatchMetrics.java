package github.lms.lemuel.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 정산 배치 작업 메트릭 수집
 *
 * Prometheus, Grafana 등 외부 모니터링 시스템과 연동
 * 히스토그램을 통해 데이터 양과 처리 시간의 상관관계 추적
 */
@Component
public class SettlementBatchMetrics {

    private final MeterRegistry meterRegistry;

    private final Counter settlementCreatedCounter;
    private final Counter settlementConfirmedCounter;
    private final Counter adjustmentConfirmedCounter;

    private final Timer settlementCreationTimer;
    private final Timer settlementConfirmationTimer;
    private final Timer adjustmentConfirmationTimer;

    // 배치 처리 데이터 양 추적 (히스토그램)
    private final DistributionSummary settlementCreationDataVolume;
    private final DistributionSummary settlementConfirmationDataVolume;
    private final DistributionSummary adjustmentConfirmationDataVolume;

    // 마지막 배치 실행 시간 추적 (알림용)
    private final AtomicLong lastBatchRunTimestamp = new AtomicLong(Instant.now().getEpochSecond());

    public SettlementBatchMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // 정산 생성 건수
        this.settlementCreatedCounter = Counter.builder("settlement.batch.created")
            .description("Number of settlements created by batch")
            .tag("batch", "settlement_creation")
            .register(meterRegistry);

        // 정산 확정 건수
        this.settlementConfirmedCounter = Counter.builder("settlement.batch.confirmed")
            .description("Number of settlements confirmed by batch")
            .tag("batch", "settlement_confirmation")
            .register(meterRegistry);

        // 정산 조정 확정 건수
        this.adjustmentConfirmedCounter = Counter.builder("settlement.batch.adjustment_confirmed")
            .description("Number of settlement adjustments confirmed by batch")
            .tag("batch", "adjustment_confirmation")
            .register(meterRegistry);

        // 정산 생성 배치 실행 시간 (히스토그램 활성화)
        this.settlementCreationTimer = Timer.builder("settlement_creation_duration_seconds")
            .description("Duration of settlement creation batch in seconds")
            .tag("batch", "settlement_creation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofSeconds(1))
            .maximumExpectedValue(Duration.ofMinutes(10))
            .register(meterRegistry);

        // 정산 확정 배치 실행 시간 (히스토그램 활성화)
        this.settlementConfirmationTimer = Timer.builder("settlement_confirmation_duration_seconds")
            .description("Duration of settlement confirmation batch in seconds")
            .tag("batch", "settlement_confirmation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofSeconds(1))
            .maximumExpectedValue(Duration.ofMinutes(10))
            .register(meterRegistry);

        // 정산 조정 확정 배치 실행 시간 (히스토그램 활성화)
        this.adjustmentConfirmationTimer = Timer.builder("adjustment_confirmation_duration_seconds")
            .description("Duration of settlement adjustment confirmation batch in seconds")
            .tag("batch", "adjustment_confirmation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMillis(100))
            .maximumExpectedValue(Duration.ofMinutes(5))
            .register(meterRegistry);

        // 배치 처리 데이터 양 (히스토그램) - 성능 최적화 판단 기준
        this.settlementCreationDataVolume = DistributionSummary.builder("settlement_creation_data_volume")
            .description("Number of records processed in settlement creation batch")
            .baseUnit("records")
            .tag("batch", "settlement_creation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .minimumExpectedValue(1.0)
            .maximumExpectedValue(10000.0)
            .register(meterRegistry);

        this.settlementConfirmationDataVolume = DistributionSummary.builder("settlement_confirmation_data_volume")
            .description("Number of records processed in settlement confirmation batch")
            .baseUnit("records")
            .tag("batch", "settlement_confirmation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .minimumExpectedValue(1.0)
            .maximumExpectedValue(10000.0)
            .register(meterRegistry);

        this.adjustmentConfirmationDataVolume = DistributionSummary.builder("adjustment_confirmation_data_volume")
            .description("Number of records processed in adjustment confirmation batch")
            .baseUnit("records")
            .tag("batch", "adjustment_confirmation")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .minimumExpectedValue(1.0)
            .maximumExpectedValue(5000.0)
            .register(meterRegistry);

        // 마지막 배치 실행 시간 (Gauge)
        Gauge.builder("settlement_batch_last_run_timestamp_seconds", lastBatchRunTimestamp, AtomicLong::get)
            .description("Unix timestamp of last batch run")
            .register(meterRegistry);
    }

    public void incrementSettlementCreated(int count) {
        settlementCreatedCounter.increment(count);
    }

    public void incrementSettlementConfirmed(int count) {
        settlementConfirmedCounter.increment(count);
    }

    public void incrementAdjustmentConfirmed(int count) {
        adjustmentConfirmedCounter.increment(count);
    }

    /**
     * 배치 실패 기록
     *
     * tag key를 "batch"로 통일해서,
     * created/confirmed 타이머/카운터와 같은 차원으로 조회 가능하게 만듦
     */
    public void recordBatchFailure(String batchName) {
        Counter.builder("settlement.batch.failures")
            .description("Number of batch job failures")
            .tag("batch", batchName)
            .register(meterRegistry)
            .increment();
    }

    public void recordSettlementCreationTime(Duration duration) {
        settlementCreationTimer.record(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void recordSettlementConfirmationTime(Duration duration) {
        settlementConfirmationTimer.record(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void recordAdjustmentConfirmationTime(Duration duration) {
        adjustmentConfirmationTimer.record(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Timer.Sample 기반 측정 (registry 파라미터 받을 필요 없음)
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopAndRecordSettlementCreation(Timer.Sample sample) {
        sample.stop(settlementCreationTimer);
    }

    public void stopAndRecordSettlementConfirmation(Timer.Sample sample) {
        sample.stop(settlementConfirmationTimer);
    }

    public void stopAndRecordAdjustmentConfirmation(Timer.Sample sample) {
        sample.stop(adjustmentConfirmationTimer);
    }

    /**
     * 배치 처리 데이터 양 기록 (히스토그램)
     *
     * 데이터 양과 처리 시간의 상관관계를 분석하여 성능 최적화 시점 판단
     */
    public void recordSettlementCreationDataVolume(int recordCount) {
        settlementCreationDataVolume.record(recordCount);
    }

    public void recordSettlementConfirmationDataVolume(int recordCount) {
        settlementConfirmationDataVolume.record(recordCount);
    }

    public void recordAdjustmentConfirmationDataVolume(int recordCount) {
        adjustmentConfirmationDataVolume.record(recordCount);
    }

    /**
     * 마지막 배치 실행 시간 업데이트 (알림용)
     */
    public void updateLastBatchRunTimestamp() {
        lastBatchRunTimestamp.set(Instant.now().getEpochSecond());
    }
}
