package github.lms.lemuel.tax.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.in.ExtractTaxInvoiceScanUseCase.UploadTaxInvoiceScanCommand;
import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.application.port.in.ReviewTaxInvoiceScanUseCase;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세금계산서 스캔 OCR — 실 PostgreSQL(Flyway + {@code ddl-auto=validate})로 end-to-end 검증한다.
 *
 * <ol>
 *   <li>스키마 ↔ 엔티티 정합(validate): 마이그레이션과 매핑이 어긋나면 컨텍스트 로드 자체가 실패한다.</li>
 *   <li>업로드 → 추출 → 발행분 자동 대사(MATCHED / MISMATCHED).</li>
 *   <li>★ 멱등: 같은 파일 재업로드가 새 행을 만들지 않는다((seller_id, file_hash) UNIQUE).</li>
 *   <li>★ PII: 사업자등록번호가 평문으로 저장되지 않는다(앱단 AES-GCM enc:v1).</li>
 * </ol>
 *
 * <p>OCR 프로바이더는 기본값(text-layer)이라 네트워크·API 키 없이 결정적으로 돈다.
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
class TaxInvoiceScanIntegrationIT {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
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

    private static final Long SELLER = 7L;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 1);

    @Autowired ExtractTaxInvoiceScanUseCase extractUseCase;
    @Autowired GetTaxInvoiceScanUseCase getUseCase;
    @Autowired ReviewTaxInvoiceScanUseCase reviewUseCase;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE public.tax_invoice_scans, public.tax_invoices, public.settlements "
                + "RESTART IDENTITY CASCADE");
    }

    private static UploadTaxInvoiceScanCommand upload(String supply, String tax, String total,
                                                      String approvalNumber) {
        String text = """
                전자세금계산서
                공급자 등록번호: 101-81-00001
                공급받는자 등록번호: 220-81-00001
                작성일자: 2026-08-01
                공급가액: %s
                세액: %s
                합계금액: %s
                승인번호: %s
                """.formatted(supply, tax, total, approvalNumber);
        return new UploadTaxInvoiceScanCommand(SELLER, "invoice.txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));
    }

    /** 대사 상대가 되는 발행 세금계산서(정산 1건 + 계산서 1건)를 시딩한다. */
    private void seedIssuedInvoice(long settlementId, long sellerId, String supply, String tax) {
        jdbc.update("""
                INSERT INTO public.settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   confirmed_at, version, created_at, updated_at)
                VALUES (?, ?, ?, 1000000.00, 0.00, 35000.00, 0.0350, 965000.00, 0.00, 0.0000, false, ?,
                        'DONE', now(), 0, now(), now())
                """, settlementId, settlementId, settlementId + 1, DATE);
        BigDecimal supplyAmount = new BigDecimal(supply);
        BigDecimal taxAmount = new BigDecimal(tax);
        jdbc.update("""
                INSERT INTO public.tax_invoices
                  (settlement_id, seller_id, supply_amount, tax_amount, total_amount, issue_date,
                   issue_number, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """, settlementId, sellerId, supplyAmount, taxAmount, supplyAmount.add(taxAmount), DATE,
                "TI-" + String.format("%010d", settlementId));
    }

    @Test
    @DisplayName("업로드 → 추출 → 발행분과 금액 일치 → MATCHED 로 저장된다")
    void uploadAndMatch() {
        seedIssuedInvoice(5L, SELLER, "1000000", "100000");

        TaxInvoiceScan scan = extractUseCase.extract(upload("1,000,000", "100,000", "1,100,000", "TI-0000000005"));

        assertThat(scan.getId()).isNotNull();
        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
        assertThat(scan.getLinkedTaxInvoiceId()).isNotNull();
        assertThat(getUseCase.byId(scan.getId())).isPresent();
    }

    @Test
    @DisplayName("금액이 어긋나면 MISMATCHED 로 남고 리뷰 큐에 뜬다")
    void mismatchGoesToReviewQueue() {
        seedIssuedInvoice(5L, SELLER, "900000", "90000");

        TaxInvoiceScan scan = extractUseCase.extract(upload("1,000,000", "100,000", "1,100,000", "TI-0000000005"));

        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.MISMATCHED);
        List<TaxInvoiceScan> queue = getUseCase.byStatuses(List.of(TaxInvoiceScanStatus.MISMATCHED), 10);
        assertThat(queue).extracting(TaxInvoiceScan::getId).contains(scan.getId());
    }

    @Test
    @DisplayName("★ 같은 파일 재업로드는 새 행을 만들지 않는다 (seller_id, file_hash) UNIQUE 멱등")
    void reuploadIsIdempotent() {
        extractUseCase.extract(upload("1,000,000", "100,000", "1,100,000", "TI-0000000005"));
        extractUseCase.extract(upload("1,000,000", "100,000", "1,100,000", "TI-0000000005"));

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM public.tax_invoice_scans", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 사업자등록번호는 평문으로 저장되지 않는다 (앱단 AES-GCM)")
    void businessNumberIsEncryptedAtRest() {
        TaxInvoiceScan scan = extractUseCase.extract(upload("1,000,000", "100,000", "1,100,000", "TI-0000000005"));

        String stored = jdbc.queryForObject(
                "SELECT supplier_business_no_enc FROM public.tax_invoice_scans WHERE id = ?",
                String.class, scan.getId());

        assertThat(stored).isNotNull().startsWith("enc:v1:").doesNotContain("1018100001");
        // 복호화 왕복은 도메인 값으로 확인 — 마스킹된 표시값이 원문에서 파생된다
        assertThat(getUseCase.byId(scan.getId()).orElseThrow().getExtracted().supplier().masked())
                .isEqualTo("101-81-*****");
    }

    @Test
    @DisplayName("발행이 뒤늦게 생기면 재대사로 UNMATCHED → MATCHED")
    void rematchAfterIssue() {
        TaxInvoiceScan scan = extractUseCase.extract(upload("1,000,000", "100,000", "1,100,000", "TI-0000000005"));
        assertThat(scan.getStatus()).isEqualTo(TaxInvoiceScanStatus.UNMATCHED);

        seedIssuedInvoice(5L, SELLER, "1000000", "100000");
        TaxInvoiceScan rematched = reviewUseCase.rematch(scan.getId());

        assertThat(rematched.getStatus()).isEqualTo(TaxInvoiceScanStatus.MATCHED);
    }

    @Test
    @DisplayName("반려는 종결 — DB 에도 REJECTED 로 남는다")
    void rejectPersists() {
        TaxInvoiceScan scan = extractUseCase.extract(upload("1,000,000", "100,000", "1,100,000", "TI-0000000005"));

        reviewUseCase.reject(scan.getId(), "위조 의심");

        String status = jdbc.queryForObject(
                "SELECT status FROM public.tax_invoice_scans WHERE id = ?", String.class, scan.getId());
        assertThat(status).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("리뷰 큐는 보류·불일치·미매칭을 한 번에 걷어온다 — 상태별로 쪼개 보지 않아도 된다")
    void reviewQueueSpansEveryHumanAttentionStatus() {
        // 보류(EXTRACTED) — 텍스트 파서는 신뢰도 1.0 이므로 산술 불일치로 needsReview 를 만든다.
        // 세액 50,000 은 공급가액 1,000,000 의 10%(=100,000)가 아니다 → vatConsistent 실패.
        TaxInvoiceScan held = extractUseCase.extract(
                upload("1,000,000", "50,000", "1,050,000", "TI-0000000007"));
        // 미매칭(UNMATCHED) — 발행분이 없는 승인번호
        TaxInvoiceScan unmatched = extractUseCase.extract(
                upload("2,000,000", "200,000", "2,200,000", "TI-0000000009"));

        assertThat(held.getStatus()).isEqualTo(TaxInvoiceScanStatus.EXTRACTED);
        assertThat(unmatched.getStatus()).isEqualTo(TaxInvoiceScanStatus.UNMATCHED);

        List<TaxInvoiceScan> queue = getUseCase.byStatuses(
                List.of(TaxInvoiceScanStatus.EXTRACTED, TaxInvoiceScanStatus.MISMATCHED,
                        TaxInvoiceScanStatus.UNMATCHED), 50);

        assertThat(queue).extracting(TaxInvoiceScan::getId)
                .contains(held.getId(), unmatched.getId());
    }
}
