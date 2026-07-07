package github.lms.lemuel.market.adapter.in.web;

import github.lms.lemuel.market.application.port.in.SyncResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KRX 수집 배치의 단일 실행 상태 보드 (인메모리).
 *
 * <p>수집은 data.go.kr 쿼터를 나눠 쓰는 배치라 동시 1건만 허용한다 —
 * {@link #tryStart} 가 CAS 로 선점하고, 실행 중이면 false 를 돌려 409 처리를 맡긴다.
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
    }

    public void fail(String error) {
        Status current = status.get();
        status.set(new Status(State.FAILED, current.job(), current.startedAt(), Instant.now(), null, error));
    }

    public Status current() {
        return status.get();
    }
}
