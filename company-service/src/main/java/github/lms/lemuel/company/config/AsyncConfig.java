package github.lms.lemuel.company.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * 뉴스 수집 배치(외부 API 쿼터 보호 간격 포함, 수 분 소요 가능)를 요청 스레드에서 떼어내기 위한
 * 실행기. 가상 스레드 1개면 충분 — 수집은 CollectStatusTracker 가 동시 1건으로 직렬화한다.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "collectTaskExecutor")
    public TaskExecutor collectTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("company-collect-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
