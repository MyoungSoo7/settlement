package github.lms.lemuel.board.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 스캔 범위 — board 영속 어댑터만.
 *
 * <p>앱 클래스가 아니라 별도 {@code @Configuration} 에 두는 이유: {@code @SpringBootConfiguration}
 * 에 {@code @EnableJpaRepositories} 가 붙어 있으면 {@code @WebMvcTest} 웹 슬라이스가 JPA 를
 * 강제로 물어 컨텍스트가 깨진다(company-service 에서 확인된 함정).
 */
@Configuration
@EntityScan(basePackages = "github.lms.lemuel.board.adapter.out.persistence")
@EnableJpaRepositories(basePackages = "github.lms.lemuel.board.adapter.out.persistence")
public class PersistenceConfig {
}
