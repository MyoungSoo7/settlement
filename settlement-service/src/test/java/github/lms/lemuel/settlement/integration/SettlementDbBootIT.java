package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0020 Phase 4 — settlement_db 물리 분리 가능성 검증.
 *
 * <p>settlement-service 가 <b>order 테이블 없이</b> 자체 엔티티만으로 격리된 DB(settlement_db)에
 * 완결적 스키마를 구성해 부팅함을 입증한다. Phase 3(읽기 컷오버) 완료로 settlement 는 더 이상 order
 * 엔티티(orders/payments/users/products)를 매핑하지 않으므로, Hibernate 가 생성하는 스키마에는
 * settlement 소유 테이블(settlements/ledger/payout/chargeback/projection 등)만 존재한다.
 *
 * <p>이 테스트는 동시에 Hibernate schema-export 로 운영 Flyway 베이스라인의 원천 DDL 을
 * {@code build/generated/settlement_db_schema.sql} 에 생성한다(Chunk 2 에서 V1 베이스라인으로 승격).
 *
 * <p>Flyway 는 끄고(ddl-auto=create) Hibernate 가 스키마를 만든다 — 24개 opslab 마이그레이션의 누적
 * 결과를 손으로 재구성하는 대신 엔티티를 단일 진실원천으로 삼는다.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.properties.hibernate.default_schema=public",
                // jakarta schema-generation 으로 DB 생성 + 스크립트(운영 Flyway 베이스라인 원천) 동시 출력
                "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=create",
                "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
                "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=build/generated/settlement_db_schema.sql",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class SettlementDbBootIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> SETTLEMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_db").withUsername("test").withPassword("test");

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SETTLEMENT_DB::getJdbcUrl);
        r.add("spring.datasource.username", SETTLEMENT_DB::getUsername);
        r.add("spring.datasource.password", SETTLEMENT_DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SpringDataSettlementJpaRepository settlementRepository;

    @Test
    @DisplayName("Phase 4: settlement 가 order 테이블 없이 격리 DB(settlement_db)에 자체 스키마로 부팅된다")
    void boots_withSelfContainedSchema_noOrderTables() {
        // settlement 소유 테이블은 생성됨
        assertThat(tableCount("settlements")).isEqualTo(1);
        assertThat(tableCount("ledger_entries")).isEqualTo(1);
        assertThat(tableCount("settlement_payment_view")).isEqualTo(1);
        assertThat(tableCount("settlement_order_view")).isEqualTo(1);

        // ★ order 소유 테이블은 존재하지 않음 — 물리 분리 가능(cross-DB 의존 0) 증거
        assertThat(orderOwnedTableCount())
                .as("settlement_db 에는 orders/payments/users/products 가 없어야 한다")
                .isZero();

        // 스키마가 실제 사용 가능함 (settlement 리포지토리 동작)
        assertThat(settlementRepository.count()).isZero();
    }

    private Integer tableCount(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table);
    }

    private Integer orderOwnedTableCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('orders','payments','users','products')",
                Integer.class);
    }
}
