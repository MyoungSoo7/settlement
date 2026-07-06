package github.lms.lemuel.financial.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * DART 수집 배치(수 분 소요)를 요청 스레드에서 떼어내기 위한 실행기.
 * 가상 스레드 1개면 충분 — 수집은 SyncStatusTracker 가 동시 1건으로 직렬화한다.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "syncTaskExecutor")
    public TaskExecutor syncTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("financial-sync-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
