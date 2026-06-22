package github.lms.lemuel;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;

/**
 * settlement-service 독립 실행 진입점 (ADR 0020 — standalone + DB-per-service).
 *
 * <p>이전에는 라이브러리 모드로 order-service fat jar 에 번들됐으나, 자체 실행가능 jar 로 독립
 * 기동(:8082)하도록 승격했다. {@code github.lms.lemuel} 루트에서 컴포넌트 스캔하므로
 * settlement-service main + shared-common 빈만 잡힌다 (order-service 는 이 모듈의 classpath 에
 * 없어 MSA 코드 경계가 그대로 유지된다).
 *
 * <p>Phase 4~5 완료: 프로세스·DB 모두 독립. 자체 {@code settlement_db} 를 소유하고 자체 Flyway
 * (V1 베이스라인)로 스키마를 생성한다. order 데이터는 Kafka 이벤트로 적재한 로컬 프로젝션
 * (settlement_*_view)으로만 읽고, 대사(audit)는 order 내부 API({@code OrderReconClient})로 order 의
 * 자기 합계를 받아 비교한다 — settlement 는 order DB 를 직접 읽지 않는다(cross-DB 연결 0).
 */
@SpringBootApplication
public class SettlementServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
