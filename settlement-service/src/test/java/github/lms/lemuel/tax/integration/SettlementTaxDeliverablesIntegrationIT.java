package github.lms.lemuel.tax.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.tax.application.TaxPostingResult;
import github.lms.lemuel.tax.application.port.in.GetTaxReconciliationUseCase;
import github.lms.lemuel.tax.application.port.in.IssueTaxInvoiceUseCase;
import github.lms.lemuel.tax.application.port.in.PostSettlementTaxUseCase;
import github.lms.lemuel.tax.application.port.in.RegisterSellerTaxProfileUseCase;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.TaxReconciliation;
import github.lms.lemuel.tax.domain.TaxType;
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
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seed B2 — 정산 연계 세무 산출물 end-to-end 를 실 PostgreSQL(Flyway validate)로 검증한다
 * (2026-07-24 정정 — VAT 포함과세 + 원천징수 실지급 통합·실효 대사로 재작성).
 *
 * <ol>
 *   <li>정산확정 → VAT 전표(포함과세 1건) 전기 + 세금계산서 발행(합계=commission) + 3자 대사 0 불일치.</li>
 *   <li>세무 전표 차대 균형(Dr COMMISSION_REVENUE≠Cr VAT_PAYABLE, 양수) + VAT_PAYABLE CHECK 통과.</li>
 *   <li>멱등 재전기 skip. 사업자 셀러 VAT 만. 미등록 셀러 보류(전표 0).</li>
 *   <li>★ 실효 대사: 실제 payout 금액과 원천징수 계산을 교차검증해 "장부만 계산하고 실지급은 반영 안 한"
 *       HIGH #4 류 결함을 검출한다(원천징수는 settlement 자체원장이 아니라 account-service GL 로 이관됐으므로,
 *       이 IT 는 payouts 테이블에 실제 지급 시나리오를 직접 시딩해 검증한다 — 배치 전체 구동은 범위 밖).</li>
 * </ol>
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
class SettlementTaxDeliverablesIntegrationIT {

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

    @Autowired RegisterSellerTaxProfileUseCase registerProfile;
    @Autowired PostSettlementTaxUseCase postTax;
    @Autowired IssueTaxInvoiceUseCase issueInvoice;
    @Autowired GetTaxReconciliationUseCase reconcile;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE public.tax_invoices, public.seller_tax_profiles, "
                + "public.payouts, public.ledger_entries, public.settlements RESTART IDENTITY CASCADE");
    }

    private void seedSettlement(long id, String payment, String commission, String net, String status) {
        jdbc.update("""
                INSERT INTO public.settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   confirmed_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0.00, ?, 0.0350, ?, 0.00, 0.0000, false, ?, ?,
                        now(), 0, now(), now())
                """, id, id, id + 1, new BigDecimal(payment), new BigDecimal(commission),
                new BigDecimal(net), DATE, status);
    }

    /** 실제 IMMEDIATE Payout 을 직접 시딩 — 배치(SettlementConfirmItemWriter) 전체 구동은 이 IT 범위 밖. */
    private void seedImmediatePayout(long settlementId, long sellerId, String amount) {
        jdbc.update("""
                INSERT INTO public.payouts
                  (settlement_id, seller_id, amount, bank_code, bank_account_number, account_holder_name,
                   status, payout_type, retry_count, version, requested_at, created_at, updated_at)
                VALUES (?, ?, ?, '004', '110-1234-5678', '홍길동',
                        'COMPLETED', 'IMMEDIATE', 0, 0, now(), now(), now())
                """, settlementId, sellerId, new BigDecimal(amount));
    }

    private int taxEntryCount(long settlementId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.ledger_entries WHERE reference_id = ? AND reference_type = 'SETTLEMENT_TAX'",
                Integer.class, settlementId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("개인 셀러: 확정정산 → VAT 전표 1건(포함과세) 전기 + 세금계산서(합계=commission) + 실제 원천징수 payout 이면 3자 대사 matched")
    void individualSeller_endToEnd_reconciles() {
        long settlementId = 5001L;
        long sellerId = 7L;
        seedSettlement(settlementId, "100000", "3500", "96500", "DONE");
        registerProfile.register(sellerId, TaxType.INDIVIDUAL, null);

        TaxPostingResult posting = postTax.postForSettlement(settlementId, sellerId);
        assertThat(posting.outcome()).isEqualTo(TaxPostingResult.Outcome.POSTED);
        assertThat(posting.entriesPosted()).isEqualTo(1); // VAT 만(원천징수는 settlement 원장에 없음)

        Optional<TaxInvoice> invoice = issueInvoice.issueForSettlement(settlementId, sellerId);
        assertThat(invoice).isPresent();
        assertThat(invoice.get().getSupplyAmount()).isEqualByComparingTo("3182"); // 3500 - 318(vat)
        assertThat(invoice.get().getTaxAmount()).isEqualByComparingTo("318");
        assertThat(invoice.get().getTotalAmount()).isEqualByComparingTo("3500.00"); // = commission

        // 실제 payout: 96500(홀드백 없음) - withholding(floor(96500*0.033)=3184) = 93316.
        seedImmediatePayout(settlementId, sellerId, "93316");

        TaxReconciliation recon = reconcile.reconcile(settlementId, sellerId);
        assertThat(recon.matched()).isTrue();
        assertThat(recon.mismatches()).isEmpty();
        assertThat(recon.ledgerVatAccrued()).isEqualByComparingTo("318");
        assertThat(recon.actualWithholdingDeducted()).isEqualByComparingTo("3184");

        // 세무 전표 차대 균형: VAT 전표 1건, Dr COMMISSION_REVENUE ≠ Cr VAT_PAYABLE, 양수, POSTED.
        Integer balancedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.ledger_entries WHERE reference_id = ? AND reference_type = 'SETTLEMENT_TAX' "
                        + "AND debit_account <> credit_account AND amount > 0 AND status = 'POSTED'",
                Integer.class, settlementId);
        assertThat(balancedRows).isEqualTo(1);

        BigDecimal vat = jdbc.queryForObject(
                "SELECT amount FROM public.ledger_entries WHERE reference_id = ? AND credit_account = 'VAT_PAYABLE'",
                BigDecimal.class, settlementId);
        assertThat(vat).isEqualByComparingTo("318");
        String debitAccount = jdbc.queryForObject(
                "SELECT debit_account FROM public.ledger_entries WHERE reference_id = ? AND credit_account = 'VAT_PAYABLE'",
                String.class, settlementId);
        assertThat(debitAccount).isEqualTo("COMMISSION_REVENUE");
    }

    @Test
    @DisplayName("★ HIGH #4 재현: 원천징수를 반영하지 않은 실제 payout(전액 지급)은 대사 불일치로 검출된다")
    void withholdingNotDeductedFromActualPayout_isDetectedAsMismatch() {
        long settlementId = 5005L;
        long sellerId = 10L;
        seedSettlement(settlementId, "100000", "3500", "96500", "DONE");
        registerProfile.register(sellerId, TaxType.INDIVIDUAL, null);
        postTax.postForSettlement(settlementId, sellerId);
        issueInvoice.issueForSettlement(settlementId, sellerId);

        // 결함 재현 — 원천징수(3184)를 공제하지 않고 net 전액(96500)을 그대로 지급한 실제 payout.
        seedImmediatePayout(settlementId, sellerId, "96500");

        TaxReconciliation recon = reconcile.reconcile(settlementId, sellerId);

        assertThat(recon.matched()).isFalse();
        assertThat(recon.actualWithholdingDeducted()).isEqualByComparingTo("0"); // 실제 감액분 없음
        assertThat(recon.mismatches()).anyMatch(c -> c.name().equals("원천징수=실제payout감액분"));
    }

    @Test
    @DisplayName("멱등 재전기는 세무 전표를 늘리지 않는다")
    void reposting_isIdempotent() {
        long settlementId = 5002L;
        long sellerId = 8L;
        seedSettlement(settlementId, "100000", "3500", "96500", "DONE");
        registerProfile.register(sellerId, TaxType.INDIVIDUAL, null);

        postTax.postForSettlement(settlementId, sellerId);
        TaxPostingResult second = postTax.postForSettlement(settlementId, sellerId);

        assertThat(second.outcome()).isEqualTo(TaxPostingResult.Outcome.ALREADY_POSTED);
        assertThat(taxEntryCount(settlementId)).isEqualTo(1);
    }

    @Test
    @DisplayName("사업자 셀러: VAT 전표만, payout 미시딩 시 원천징수 축은 평가 skip 되고 matched")
    void businessSeller_vatOnly() {
        long settlementId = 5003L;
        long sellerId = 9L;
        seedSettlement(settlementId, "100000", "3500", "96500", "DONE");
        registerProfile.register(sellerId, TaxType.BUSINESS, "1234567890");

        TaxPostingResult posting = postTax.postForSettlement(settlementId, sellerId);
        issueInvoice.issueForSettlement(settlementId, sellerId);

        assertThat(posting.entriesPosted()).isEqualTo(1);
        assertThat(taxEntryCount(settlementId)).isEqualTo(1);

        TaxReconciliation recon = reconcile.reconcile(settlementId, sellerId);
        assertThat(recon.matched()).isTrue();
        assertThat(recon.actualWithholdingDeducted()).isNull(); // payout 미시딩 — 평가 skip
    }

    @Test
    @DisplayName("미등록 셀러는 세무 산출 보류 — 전표 0건")
    void unregisteredSeller_isPending() {
        long settlementId = 5004L;
        long sellerId = 404L;
        seedSettlement(settlementId, "100000", "3500", "96500", "DONE");

        TaxPostingResult posting = postTax.postForSettlement(settlementId, sellerId);

        assertThat(posting.outcome()).isEqualTo(TaxPostingResult.Outcome.PENDING_NO_PROFILE);
        assertThat(taxEntryCount(settlementId)).isZero();
        assertThat(issueInvoice.issueForSettlement(settlementId, sellerId)).isEmpty();
    }
}
