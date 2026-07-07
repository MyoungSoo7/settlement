package github.lms.lemuel.ai;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ai-service 독립 부팅 진입점 — 대화형 AI 챗봇 서비스 (docs/design/ai-service-phase1.md).
 *
 * <p>자체 DB(lemuel_ai) 를 소유하는 DB-per-service. Phase 1 은 Claude 대화(컨텍스트 유지) +
 * 대화 이력 관리까지이며, Function Calling(Phase 2)·RAG(Phase 3)는 이 골격 위에 얹는다.
 *
 * <p>★ 스캔 범위를 {@code github.lms.lemuel.ai} + {@code github.lms.lemuel.common.config.jwt} 로
 * <b>한정</b>한다 — LLM 호출은 실비용이라 익명 개방이 불가하므로 shared-common 의 JWT 스택
 * (JwtUtil/JwtAuthenticationFilter/전역 SecurityConfig)만 물고, Outbox·audit 등 나머지 인프라는
 * 제외한다(Phase 1 은 이벤트 발행 없음 — company-service 의 제한 스캔 철학과 동일).
 * JPA 엔티티/리포지토리는 기본 스캔(이 클래스의 패키지 하위)만으로 충분하다.
 */
@SpringBootApplication(scanBasePackages = {
        "github.lms.lemuel.ai",
        "github.lms.lemuel.common.config.jwt"
})
@ConfigurationPropertiesScan(basePackages = "github.lms.lemuel.ai")
public class AiServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
