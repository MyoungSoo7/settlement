package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * deposit-service 독립 부팅 진입점.
 *
 * <p>★ 자체 DB(lemuel_deposit) 를 소유하는 DB-per-service 이므로 독립 {@code @SpringBootApplication} 을 가진다
 * (card/organization/investment/loan 패턴 미러링).
 *
 * <p>루트 {@code github.lms.lemuel} 에서 스캔 → deposit 패키지 + shared-common(JWT SecurityConfig·Outbox·
 * 멱등 인프라·Audit·ShedLock) 빈만 잡힌다. 타 서비스 패키지는 build.gradle.kts 의존에 없어 클래스패스에 없다.
 *
 * <p>JPA Auditing 은 {@code deposit.config.JpaAuditingConfig} 로 분리했다 — 여기에 두면
 * {@code @WebMvcTest} 슬라이스가 JPA 메타모델 없이 {@code jpaAuditingHandler} 를 만들려다 전부 깨진다.
 *
 * <p>{@code @EnableScheduling} 은 만료 hold 회수 배치({@code DepositHoldExpiryScheduler})에 필요하다.
 * 이게 꺼져 있으면 스케줄러 빈은 정상 생성되지만 <b>한 번도 호출되지 않는다</b> — 기동 로그에도
 * 아무 경고가 없어서, 만료된 hold 가 계속 쌓이는 동안 배선이 살아 있다고 착각하기 쉽다.
 * ShedLock 은 shared-common 의 {@code SchedulingLockConfig} 가 함께 켠다.
 *
 * <p>배포 단위(ADR 0020): 이 모듈은 {@code ghcr.io/myoungsoo7/settlement-deposit} 이미지 한 장으로 나간다.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class DepositServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(DepositServiceApplication.class, args);
    }
}
