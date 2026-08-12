package github.lms.lemuel.ai.config;

import github.lms.lemuel.ai.chat.application.port.out.RetrieveContextPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RAG 가 꺼져 있을 때의 {@link RetrieveContextPort} 기본 구현.
 *
 * <p>ChatService 는 이 포트를 <b>항상</b> 주입받는다 — {@code @Nullable} 이나
 * {@code ObjectProvider} 로 "있을 수도 없을 수도" 만들면 null 분기가 서비스에 새어 들고
 * 테스트 경로가 둘로 갈린다. 대신 "아무것도 찾지 않는 구현"을 항상 하나 꽂아 두고,
 * RAG 를 켜면 {@code KnowledgeRetrievalService} 가 그 자리를 차지한다.
 *
 * <p>조건은 {@code havingValue="true"} 와 {@code havingValue="false", matchIfMissing=true} 의
 * 상호배타 쌍이다 — {@code @ConditionalOnMissingBean} 의 등록 순서 의존성을 피하기 위해
 * (provider 어댑터 선택과 같은 패턴) 명시적 배타 조건을 쓴다.
 */
@Configuration
public class RagWiringConfig {

    /** RAG 비활성(기본) — 근거 0건. 프롬프트는 Phase 1 과 바이트 동일해진다. */
    @Bean
    @ConditionalOnProperty(name = "app.ai.rag.enabled", havingValue = "false", matchIfMissing = true)
    public RetrieveContextPort noRetrievalPort() {
        return userMessage -> List.of();
    }
}
