package github.lms.lemuel.company.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA/Outbox 영속성 설정 — company 도메인 + shared-common Outbox·멱등 인프라의 엔티티·리포지토리·
 * 컴포넌트 스캔을 한곳에 모은다.
 *
 * <p>★ 이 설정을 {@link github.lms.lemuel.company.CompanyServiceApplication} 에서 분리한 이유:
 * {@code @EnableJpaRepositories}/{@code @EntityScan} 가 앱 클래스(=@SpringBootConfiguration)에 붙어
 * 있으면 {@code @WebMvcTest} 웹 슬라이스에서도 JPA 리포지토리가 강제 생성되어 EntityManager 부재로
 * 컨텍스트 로드가 깨진다. {@code @Configuration} 클래스는 @WebMvcTest 컴포넌트 스캔에서 제외되므로
 * (그래서 슬라이스는 SecurityConfig 를 명시적 @Import 한다) 여기로 옮기면 웹 슬라이스가 JPA 를 물지
 * 않는다. 런타임(전체 부팅)에서는 company 패키지 스캔이 이 설정을 그대로 픽업한다.
 */
@Configuration
@ComponentScan(basePackages = "github.lms.lemuel.common.outbox")
@EntityScan(basePackages = {
        "github.lms.lemuel.company",
        "github.lms.lemuel.common.outbox"
})
@EnableJpaRepositories(basePackages = {
        "github.lms.lemuel.company",
        "github.lms.lemuel.common.outbox"
})
public class PersistenceConfig {
}
