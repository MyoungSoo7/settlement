package github.lms.lemuel.company.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * 수 분 소요되는 배치를 요청 스레드에서 떼어내기 위한 실행기들. 각 배치는 상태 보드
 * (CollectStatusTracker · RecalcStatusTracker)가 동시 1건으로 직렬화하므로 가상 스레드 1개면 충분하다.
 *
 * <p>수집과 재계산을 별도 실행기로 두는 이유는 이름으로 스레드를 구분해 로그·스레드 덤프에서
 * 어느 배치가 도는지 바로 보이게 하기 위함이다.
 */
@Configuration
public class AsyncConfig {

    /** 뉴스 수집 — 외부 API 쿼터 보호 간격 포함. */
    @Bean(name = "collectTaskExecutor")
    public TaskExecutor collectTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("company-collect-");
        executor.setVirtualThreads(true);
        return executor;
    }

    /** 평판 재계산 — 기사별 감성분석(provider=gemini 면 외부 LLM 호출) 순차 수행. */
    @Bean(name = "recalcTaskExecutor")
    public TaskExecutor recalcTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("company-recalc-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
