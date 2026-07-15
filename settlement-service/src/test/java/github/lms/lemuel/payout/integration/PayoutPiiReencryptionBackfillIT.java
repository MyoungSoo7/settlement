package github.lms.lemuel.payout.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.payout.adapter.out.persistence.PayoutJpaEntity;
import github.lms.lemuel.payout.adapter.out.persistence.SpringDataPayoutRepository;
import github.lms.lemuel.payout.application.port.in.ReencryptPayoutPiiUseCase;
import github.lms.lemuel.payout.application.port.out.PayoutPiiBackfillPort;
import github.lms.lemuel.payout.domain.PayoutPiiBackfillReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지급계좌 PII 재암호화 백필 — 실제 PostgreSQL 로 평문→암호문 전환 / 암호문 스킵(멱등) / 건수 집계를 검증한다.
 *
 * <p>레거시 평문 행을 raw SQL 로 심고(컨버터 우회), 백필 유스케이스를 태운 뒤 raw 컬럼이 enc:v1 로 전환됐는지와
 * 엔티티 로드 시 원문으로 복호화되는지(왕복 무손실)를 확인한다. 두 번째 실행에서 0 건 백필 + ciphertext 불변으로
 * '이미 암호문인 행은 스킵'을 증명한다.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.schemas=public",
                "spring.flyway.default-schema=public",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class PayoutPiiReencryptionBackfillIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> SETTLEMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_db").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SETTLEMENT_DB::getJdbcUrl);
        r.add("spring.datasource.username", SETTLEMENT_DB::getUsername);
        r.add("spring.datasource.password", SETTLEMENT_DB::getPassword);
    }

    @Autowired ReencryptPayoutPiiUseCase useCase;
    @Autowired PayoutPiiBackfillPort backfillPort;
    @Autowired SpringDataPayoutRepository repository;
    @Autowired JdbcTemplate jdbc;

    private static final String PREFIX = "enc:v1:";

    /** 컨버터를 우회해 평문을 그대로 심는다(레거시 적재 재현). @return 생성된 payout id. */
    private long seedPlaintext(long settlementId, String account, String holder) {
        // fk_payouts_settlement(V20260715110000) — 자식 payout 은 실존 부모 정산을 요구한다.
        jdbc.update("""
                INSERT INTO public.settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   version, created_at, updated_at)
                VALUES (?, ?, ?, 10000.00, 0.00, 300.00, 0.0300, 9700.00, 0.00, 0.0000, false,
                        CURRENT_DATE, 'REQUESTED', 0, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, settlementId, settlementId, settlementId + 1);
        jdbc.update("INSERT INTO public.payouts " +
                "(settlement_id, seller_id, amount, bank_code, bank_account_number, account_holder_name, " +
                " status, retry_count, version, requested_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', 0, 0, now(), now(), now())",
                settlementId, 1L, 50000, "KB", account, holder);
        return jdbc.queryForObject(
                "SELECT id FROM public.payouts WHERE settlement_id = ?", Long.class, settlementId);
    }

    private String rawAccount(long id) {
        return jdbc.queryForObject(
                "SELECT bank_account_number FROM public.payouts WHERE id = ?", String.class, id);
    }

    private String rawHolder(long id) {
        return jdbc.queryForObject(
                "SELECT account_holder_name FROM public.payouts WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("평문 행을 암호문으로 전환하고, 재실행 시 암호문 행은 스킵한다")
    void reencryptsPlaintextThenSkipsCiphertext() {
        long a = seedPlaintext(9001L, "110-1111-000000", "김철수");
        long b = seedPlaintext(9002L, "220-2222-000000", "이영희");

        assertThat(backfillPort.countLegacyPlaintext()).isEqualTo(2L);

        PayoutPiiBackfillReport first = useCase.reencryptLegacyPlaintext(500);
        assertThat(first.backfilled()).isEqualTo(2L);
        assertThat(first.remainingPlaintext()).isZero();
        assertThat(first.complete()).isTrue();

        // raw 컬럼은 enc:v1 로 전환됐고, 엔티티 로드 시 원문으로 복호화된다(왕복 무손실).
        assertThat(rawAccount(a)).startsWith(PREFIX);
        assertThat(rawHolder(a)).startsWith(PREFIX);
        PayoutJpaEntity loadedA = repository.findById(a).orElseThrow();
        assertThat(loadedA.getBankAccountNumber()).isEqualTo("110-1111-000000");
        assertThat(loadedA.getAccountHolderName()).isEqualTo("김철수");

        String cipherB = rawAccount(b);
        assertThat(cipherB).startsWith(PREFIX);

        // 재실행: 이미 암호문이라 스킵 → 0 건, ciphertext 도 재기록되지 않는다(멱등).
        PayoutPiiBackfillReport second = useCase.reencryptLegacyPlaintext(500);
        assertThat(second.backfilled()).isZero();
        assertThat(second.complete()).isTrue();
        assertThat(rawAccount(b)).isEqualTo(cipherB);
    }

    @Test
    @DisplayName("혼합 행(한 필드만 평문)도 선택돼 두 필드 모두 암호문으로 전환된다")
    void reencryptsMixedRow() {
        long id = seedPlaintext(9003L, "330-3333-000000", "박민수");
        // 먼저 전체 암호화한 뒤, 한 필드만 평문으로 되돌려 '혼합' 레거시 행을 만든다.
        useCase.reencryptLegacyPlaintext(500);
        assertThat(rawAccount(id)).startsWith(PREFIX);
        jdbc.update("UPDATE public.payouts SET account_holder_name = ? WHERE id = ?", "정수진", id);

        // OR 조건으로 혼합 행이 잔존으로 잡힌다.
        assertThat(backfillPort.countLegacyPlaintext()).isEqualTo(1L);

        PayoutPiiBackfillReport report = useCase.reencryptLegacyPlaintext(500);
        assertThat(report.backfilled()).isEqualTo(1L);
        assertThat(report.complete()).isTrue();

        // 두 필드 모두 암호문 + 복호화 왕복 정상(계좌번호는 이중 암호화되지 않음).
        assertThat(rawAccount(id)).startsWith(PREFIX);
        assertThat(rawHolder(id)).startsWith(PREFIX);
        PayoutJpaEntity loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getBankAccountNumber()).isEqualTo("330-3333-000000");
        assertThat(loaded.getAccountHolderName()).isEqualTo("정수진");
    }

    @Test
    @DisplayName("페이지 단위 커밋 — 작은 페이지로 여러 번 나눠 전량 전환한다")
    void commitsPerPage() {
        for (long s = 9100L; s < 9105L; s++) {
            seedPlaintext(s, "440-" + s + "-000000", "홀더" + s);
        }
        assertThat(backfillPort.countLegacyPlaintext()).isEqualTo(5L);

        PayoutPiiBackfillReport report = useCase.reencryptLegacyPlaintext(2); // 2건씩 → 3페이지

        assertThat(report.backfilled()).isEqualTo(5L);
        assertThat(report.pagesCommitted()).isEqualTo(3);
        assertThat(report.remainingPlaintext()).isZero();
        assertThat(useCase.remainingPlaintextCount().remainingPlaintext()).isZero();
    }
}
