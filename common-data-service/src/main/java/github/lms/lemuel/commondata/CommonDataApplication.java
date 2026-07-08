package github.lms.lemuel.commondata;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * common-data-service 독립 부팅 진입점.
 *
 * <p>공공데이터포털(data.go.kr)의 임의 OpenAPI 를 "데이터소스"로 등록해 수집·저장·공개 조회하는
 * 범용 커넥터. 자체 DB(lemuel_commondata) 를 소유하는 DB-per-service 이며, 다른 서비스와
 * 코드·DB·이벤트 의존이 전혀 없다. 인증키는 data.go.kr 계정당 1개({@code DATA_GO_KR_API_KEY})로
 * 활용신청한 모든 API 에 공용이다.
 *
 * <p>★ 다른 서비스와 달리 베이스 패키지가 {@code github.lms.lemuel.commondata} 다 —
 * 루트 스캔 시 따라오는 shared-common(JWT·Outbox·Kafka) 이 이 서비스에는 죽은 무게라서
 * 스캔 범위를 commondata 로 한정하고 자체 최소 SecurityConfig 를 둔다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CommonDataApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(CommonDataApplication.class, args);
    }
}
