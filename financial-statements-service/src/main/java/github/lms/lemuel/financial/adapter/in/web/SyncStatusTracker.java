package github.lms.lemuel.financial.adapter.in.web;

import github.lms.lemuel.financial.application.port.in.SyncResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DART 수집 배치의 단일 실행 상태 보드 (인메모리) + Prometheus 메트릭.
 *
 * <p>수집은 DART 쿼터를 나눠 쓰는 장시간 배치라 동시 1건만 허용한다 —
 * {@link #tryStart} 가 CAS 로 선점하고, 실행 중이면 false 를 돌려 409 처리를 맡긴다.
 *
 * <p>동기화 신선도 감시용 메트릭을 노출한다(모든 sync 경로=수동+스케줄러가 여기로 수렴):
 * {@code settlement_sync_last_success_epoch_seconds}(마지막 성공 시각),
 * {@code settlement_sync_runs_total{result}}, {@code settlement_sync_rows_upserted_total}.
 */
@Component
public class SyncStatusTracker {

    public enum State { IDLE, RUNNING, DONE, FAILED }

    public record Status(State state, String job, Instant startedAt, Instant finishedAt,
                         SyncResult result, String error) {
        static Status idle() {
            return new Status(State.IDLE, null, null, null, null, null);
        }
    }

    private final AtomicReference<Status> status = new AtomicReference<>(Status.idle());

    // ── Micrometer 메트릭 ──
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter upsertedCounter;

    public SyncStatusTracker(MeterRegistry registry) {
        Gauge.builder("settlement_sync_last_success_epoch_seconds", lastSuccessEpochSeconds, AtomicLong::doubleValue)
                .description("마지막 동기화 성공 시각(epoch seconds) — 0=파드 기동 후 아직 성공 없음")
                .register(registry);
        this.successCounter = Counter.builder("settlement_sync_runs_total")
                .tag("result", "success").description("동기화 성공 횟수").register(registry);
        this.failureCounter = Counter.builder("settlement_sync_runs_total")
                .tag("result", "failure").description("동기화 실패 횟수").register(registry);
        this.upsertedCounter = Counter.builder("settlement_sync_rows_upserted_total")
                .description("동기화로 upsert 된 행 수 누계").register(registry);
    }

    /** RUNNING 이 아니면 job 실행 상태로 선점. 이미 실행 중이면 false. */
    public boolean tryStart(String job) {
        Status current = status.get();
        if (current.state() == State.RUNNING) {
            return false;
        }
        return status.compareAndSet(current,
                new Status(State.RUNNING, job, Instant.now(), null, null, null));
    }

    public void complete(SyncResult result) {
        Status current = status.get();
        status.set(new Status(State.DONE, current.job(), current.startedAt(), Instant.now(), result, null));
        lastSuccessEpochSeconds.set(Instant.now().getEpochSecond());
        upsertedCounter.increment(result.upserted());
        successCounter.increment();
    }

    public void fail(String error) {
        Status current = status.get();
        status.set(new Status(State.FAILED, current.job(), current.startedAt(), Instant.now(), null, error));
        failureCounter.increment();
    }

    public Status current() {
        return status.get();
    }
}
