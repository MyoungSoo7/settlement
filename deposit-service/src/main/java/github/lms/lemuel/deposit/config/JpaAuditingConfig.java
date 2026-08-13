package github.lms.lemuel.deposit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화.
 *
 * <p>deposit 의 JPA 엔티티(DepositAccount/Entry/Hold/OffsetShortfall)가
 * {@code @EntityListeners(AuditingEntityListener.class)} + {@code @CreatedDate}/{@code @LastModifiedDate}
 * 를 쓰므로 이 애노테이션이 <b>필수</b>다 — 리스너를 실제로 구동하는 것은 이것뿐이다.
 *
 * <p>★ {@code DepositServiceApplication} 이 아니라 별도 설정 클래스에 두는 이유:
 * {@code @EnableJpaAuditing} 은 {@code jpaAuditingHandler} → {@code jpaMappingContext} 빈을 요구하고,
 * 그 빈은 JPA 메타모델이 비어 있으면 {@code "JPA metamodel must not be empty"} 로 기동에 실패한다.
 * 애플리케이션 클래스에 붙어 있으면 JPA 를 로드하지 않는 슬라이스 테스트({@code @WebMvcTest})가
 * 전부 컨텍스트 로딩 단계에서 깨진다 — 웹 계층만 보려는 테스트가 영속 계층 때문에 못 뜨는 셈이다.
 * 별도 {@code @Configuration} 으로 분리하면 {@code WebMvcTypeExcludeFilter} 가 걸러내므로
 * 운영 부팅에서는 그대로 적용되고 웹 슬라이스에서만 빠진다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
