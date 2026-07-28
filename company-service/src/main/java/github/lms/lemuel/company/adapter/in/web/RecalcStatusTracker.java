package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.RecalcReputationUseCase.RecalcSummary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 평판 재계산 배치의 단일 실행 상태 보드 (인메모리).
 *
 * <p>재계산은 기사별 감성분석(provider=gemini 면 외부 LLM 호출)을 순차 수행하는 배치라 동시 1건만
 * 허용한다 — {@link #tryStart} 가 CAS 로 선점하고, 실행 중이면 false 를 돌려 409 처리를 맡긴다.
 * {@link CollectStatusTracker} 와 같은 모양이다(수집·재계산이 같은 트리거→폴링 규약을 쓴다).
 */
@Component
public class RecalcStatusTracker {

    public enum State { IDLE, RUNNING, DONE, FAILED }

    public record Status(State state, String job, Instant startedAt, Instant finishedAt,
                         RecalcSummary result, String error) {
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

    public void complete(RecalcSummary result) {
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
