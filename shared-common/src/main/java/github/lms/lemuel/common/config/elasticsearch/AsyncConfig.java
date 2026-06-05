package github.lms.lemuel.common.config.elasticsearch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정
 * Elasticsearch 인덱싱 이벤트를 비동기로 처리하기 위한 설정
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * 비동기 작업을 위한 ThreadPool Executor
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);           // 기본 스레드 수
        executor.setMaxPoolSize(5);            // 최대 스레드 수
        executor.setQueueCapacity(100);        // 대기 큐 크기
        executor.setThreadNamePrefix("settlement-index-");
        // 큐(100) + maxPool(5)까지 가득 차면 기본 AbortPolicy 는 예외를 던져 작업을 버린다.
        // 이 풀은 ES 인덱싱뿐 아니라 AFTER_COMMIT 원장 분개 생성도 처리하므로(유실 시 정합성 파손),
        // 거부 시 호출 스레드가 직접 실행하도록 CallerRunsPolicy 를 써서 무손실 + 백프레셔를 보장한다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 원장 분개 생성/역분개 전용 Executor.
     *
     * <p>베스트에포트 ES 인덱싱과 풀을 공유하면 인덱싱 버스트가 풀을 점유해
     * AFTER_COMMIT 원장 작업까지 지연/CallerRuns 로 밀린다. 재무 정합성에 직결되는
     * 원장 작업을 격리하기 위해 별도 풀로 분리한다.
     *
     * <p>거부 시에도 유실되면 안 되므로 CallerRunsPolicy 로 무손실 + 백프레셔를 보장한다.
     */
    @Bean(name = "ledgerTaskExecutor")
    public Executor ledgerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ledger-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
