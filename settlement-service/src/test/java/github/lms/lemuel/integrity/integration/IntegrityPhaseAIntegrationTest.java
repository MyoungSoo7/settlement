package github.lms.lemuel.integrity.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.integrity.application.port.in.IntegrityQueryUseCase;
import github.lms.lemuel.integrity.domain.HoldbackStatusReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.integrity.domain.StuckStateReport;
import github.lms.lemuel.ledger.adapter.out.persistence.LedgerEntryJpaEntity;
import github.lms.lemuel.ledger.adapter.out.persistence.LedgerOutboxJpaEntity;
import github.lms.lemuel.payout.adapter.out.persistence.PayoutJpaEntity;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrity Suite Phase A 통합 검증 — 실 PostgreSQL(Testcontainers).
 * 설계: docs/design/settlement-integrity-suite.md §4 Phase A 완료 기준.
 *
 * <p>핵심 시나리오(INV-5): 분개가 <b>통짜로 누락</b>된 확정 정산은 시산표(차/대 균형)로는
 * 절대 잡을 수 없다 — 이 원장 모델에서 분개 1 row 는 차변·대변 계정을 함께 갖는 자기균형
 * 구조라 시산표는 어떤 부분집합에 대해서도 항상 균형이다. 그 사각지대를
 * {@code ledger_completeness} 가 잡아내는 것을 입증한다.
 *
 * <p>픽스처의 정산은 NORMAL 3.5% 기본 요율 전제(payment 100,000 / commission 3,500 /
 * net 96,500)이며 {@code commission_rate} 는 엔티티 기본 스냅샷 값을 그대로 둔다
 * (스냅샷 컬럼은 생성 후 변경 금지 — 이 스위트가 지키려는 규칙 그 자체).
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.ledger-outbox.enabled=false", // 폴러가 테스트 데이터(FAILED/PENDING)를 건드리지 않게 정지
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class IntegrityPhaseAIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    /** payment_id UNIQUE 충돌 없이 테스트 간 격리하기 위한 시퀀스. */
    private static final AtomicLong SEQ = new AtomicLong(1000);

    @Autowired IntegrityQueryUseCase integrity;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    // ── INV-5 원장 완전성 ─────────────────────────────────────────────────

    @Test
    @DisplayName("INV-5: 분개가 통짜 누락된 확정 정산 — 시산표는 균형이지만 ledger_completeness 가 잡는다")
    void detectsSettlementWithNoLedgerEntryDespiteBalancedTrialBalance() {
        // 테스트가 한 컨테이너 DB 를 공유하므로 기준일을 테스트별로 분리해 스코프 오염을 차단한다.
        LocalDate target = LocalDate.now().minusDays(2);
        LocalDateTime confirmedAt = target.atTime(12, 0);

        Long okId = tx.execute(s -> {
            SettlementJpaEntity ok = settlement(target, confirmedAt);
            em.persist(ok);
            // 정상 분개 2 rows (net + commission = payment) — SingleLedgerEntryWriter 와 동형
            em.persist(entry(ok.getId(), "SETTLEMENT", new BigDecimal("96500.00"), target));
            em.persist(entry(ok.getId(), "SETTLEMENT", new BigDecimal("3500.00"), target));
            return ok.getId();
        });
        Long missingId = tx.execute(s -> {
            SettlementJpaEntity bad = settlement(target, confirmedAt); // 분개 의도적 누락
            em.persist(bad);
            return bad.getId();
        });

        LedgerCompletenessReport report = integrity.checkLedgerCompleteness(target, null);

        // 시산표 관점: 존재하는 분개(정상 정산의 2 rows)는 자기균형 — 차/대 불일치가 아니다.
        // 그런데도 완전성 검사는 누락 정산을 특정한다.
        assertThat(report.ok()).isFalse();
        assertThat(report.missingSettlementIds()).contains(missingId).doesNotContain(okId);
        assertThat(report.confirmedSettlements()).isEqualTo(2);
        assertThat(report.ledgerPostedTotal()).isEqualByComparingTo("100000.00");
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("INV-5"));
    }

    @Test
    @DisplayName("INV-5: 반쪽 분개(금액 불일치)와 grace 이내 미처리를 구분한다")
    void detectsAmountMismatchAndSeparatesGracePending() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate target = now.toLocalDate();
        // half 는 "grace 경과 + 당일(target) 안" 이어야 한다. now.minusHours(2) 는 자정 직후(예: 00~02시)
        // 실행 시 전날로 넘어가, 모든 쿼리의 business-day 창(confirmed_at >= target 0시)에서 빠져
        // amountMismatched 목록에서 사라지는 시간대 의존 flaky 를 유발했다. 당일 시작 직후로 고정해
        // 항상 당일 + grace(기본 15분) 밖이 되도록 안정화한다.
        LocalDateTime pastGraceSameDay = target.atStartOfDay().plusMinutes(1);

        Long halfId = tx.execute(s -> {
            SettlementJpaEntity half = settlement(target, pastGraceSameDay); // grace 경과 + 당일
            em.persist(half);
            em.persist(entry(half.getId(), "SETTLEMENT", new BigDecimal("96500.00"), target)); // 수수료 row 누락
            return half.getId();
        });
        Long freshId = tx.execute(s -> {
            SettlementJpaEntity fresh = settlement(target, now); // 방금 확정 — 분개는 아직 (정상 대기)
            em.persist(fresh);
            return fresh.getId();
        });

        LedgerCompletenessReport report = integrity.checkLedgerCompleteness(target, null);

        assertThat(report.amountMismatchedSettlementIds()).contains(halfId);
        assertThat(report.pendingWithinGrace()).isEqualTo(1);          // fresh 는 위반이 아니라 대기
        assertThat(report.missingSettlementIds()).doesNotContain(freshId);
        assertThat(report.ok()).isFalse();                              // 반쪽 분개 때문만으로 실패
    }

    // ── INV-6 지급 대사 ───────────────────────────────────────────────────

    @Test
    @DisplayName("INV-6: net 초과 payout 은 위반, payout 미생성은 정보성")
    void detectsOverpaidPayoutButNotMissingPayout() {
        LocalDate target = LocalDate.now().minusDays(3); // 기준일 분리 (스코프 오염 차단)
        LocalDateTime confirmedAt = target.atTime(12, 0);

        record Ids(Long overpaidSettlement, Long payoutId, Long withoutPayout) {}
        Ids ids = tx.execute(s -> {
            SettlementJpaEntity over = settlement(target, confirmedAt); // net 96,500
            em.persist(over);
            PayoutJpaEntity payout = payout(over.getId(), new BigDecimal("97000.00"), // net 초과!
                    PayoutStatus.REQUESTED, null);
            em.persist(payout);
            SettlementJpaEntity noPayout = settlement(target, confirmedAt);
            em.persist(noPayout);
            return new Ids(over.getId(), payout.getId(), noPayout.getId());
        });

        PayoutReconReport report = integrity.checkPayoutRecon(target);

        assertThat(report.ok()).isFalse();
        assertThat(report.overpaidPayouts())
                .anySatisfy(p -> {
                    assertThat(p.settlementId()).isEqualTo(ids.overpaidSettlement());
                    assertThat(p.payoutAmount()).isEqualByComparingTo("97000.00");
                    assertThat(p.netAmount()).isEqualByComparingTo("96500.00");
                });
        // payout 미생성은 목록에는 나오지만 ok 판정을 뒤집지 않는다 (정보성)
        assertThat(report.settlementsWithoutPayout()).contains(ids.withoutPayout());
        assertThat(report.reasons()).noneSatisfy(r -> assertThat(r).contains("미생성"));
    }

    // ── INV-7 홀드백 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("INV-7: 해제일 경과 미해제 홀드백만 overdue 로 판정한다")
    void detectsOverdueHoldbackOnly() {
        LocalDate today = LocalDate.now();
        record Ids(Long overdue, Long notDue) {}
        Ids ids = tx.execute(s -> {
            SettlementJpaEntity overdue = settlement(today.minusDays(40), today.minusDays(40).atTime(12, 0));
            overdue.setHoldbackAmount(new BigDecimal("28950.00"));
            overdue.setHoldbackRate(new BigDecimal("0.3000"));
            overdue.setHoldbackReleaseDate(today.minusDays(2)); // 기한 경과 & 미해제 → 위반
            em.persist(overdue);

            SettlementJpaEntity notDue = settlement(today.minusDays(4), today.minusDays(4).atTime(12, 0)); // 확정일 분리
            notDue.setHoldbackAmount(new BigDecimal("10000.00"));
            notDue.setHoldbackRate(new BigDecimal("0.1000"));
            notDue.setHoldbackReleaseDate(today.plusDays(5)); // 아직 기한 전 → 정상
            em.persist(notDue);
            return new Ids(overdue.getId(), notDue.getId());
        });

        HoldbackStatusReport report = integrity.checkHoldbackStatus();

        assertThat(report.ok()).isFalse();
        assertThat(report.overdueSettlementIds()).contains(ids.overdue()).doesNotContain(ids.notDue());
        assertThat(report.overdueAmountTotal()).isGreaterThanOrEqualTo(new BigDecimal("28950.00"));
        assertThat(report.totalHeld()).isGreaterThanOrEqualTo(new BigDecimal("38950.00"));
    }

    // ── INV-11 상태 체류 ─────────────────────────────────────────────────

    @Test
    @DisplayName("INV-11: SENDING 장기 체류 payout 과 ledger_outbox FAILED 를 감지한다")
    void detectsStuckSendingPayoutAndFailedLedgerOutbox() {
        record Ids(Long stuckPayout, Long freshPayout) {}
        // 앵커 정산은 확정일을 5일 전으로 밀어 오늘 날짜 스코프의 다른 테스트를 오염시키지 않는다.
        LocalDate anchorDate = LocalDate.now().minusDays(5);
        Ids ids = tx.execute(s -> {
            SettlementJpaEntity st = settlement(anchorDate, anchorDate.atTime(12, 0));
            em.persist(st);
            PayoutJpaEntity stuck = payout(st.getId(), new BigDecimal("50000.00"),
                    PayoutStatus.SENDING, LocalDateTime.now().minusHours(2)); // 임계(60분) 초과
            em.persist(stuck);

            SettlementJpaEntity st2 = settlement(anchorDate, anchorDate.atTime(12, 0));
            em.persist(st2);
            PayoutJpaEntity fresh = payout(st2.getId(), new BigDecimal("50000.00"),
                    PayoutStatus.SENDING, LocalDateTime.now()); // 방금 송신 — 정상
            em.persist(fresh);

            LedgerOutboxJpaEntity failedTask = new LedgerOutboxJpaEntity();
            failedTask.setTaskType("CREATE_ENTRY");
            failedTask.setSettlementId(st.getId());
            failedTask.setStatus("FAILED");
            failedTask.setLastError("simulated failure");
            em.persist(failedTask);
            return new Ids(stuck.getId(), fresh.getId());
        });

        StuckStateReport report = integrity.checkStuckStates(null);

        assertThat(report.ok()).isFalse();
        assertThat(report.stuckSendingPayouts())
                .anySatisfy(p -> assertThat(p.payoutId()).isEqualTo(ids.stuckPayout()));
        assertThat(report.stuckSendingPayouts())
                .noneSatisfy(p -> assertThat(p.payoutId()).isEqualTo(ids.freshPayout()));
        assertThat(report.ledgerOutboxFailed()).isGreaterThanOrEqualTo(1);
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("이중지급"));
    }

    // ── 픽스처 ───────────────────────────────────────────────────────────

    /** DONE 정산 — payment 100,000 / commission 3,500 / net 96,500. commission_rate 는 엔티티 기본 스냅샷 유지. */
    private static SettlementJpaEntity settlement(LocalDate settlementDate, LocalDateTime confirmedAt) {
        long seq = SEQ.incrementAndGet();
        SettlementJpaEntity e = new SettlementJpaEntity();
        e.setPaymentId(seq);
        e.setOrderId(seq);
        e.setPaymentAmount(new BigDecimal("100000.00"));
        e.setRefundedAmount(BigDecimal.ZERO);
        e.setCommission(new BigDecimal("3500.00"));
        e.setNetAmount(new BigDecimal("96500.00"));
        e.setStatus("DONE");
        e.setSettlementDate(settlementDate);
        e.setConfirmedAt(confirmedAt);
        e.setHoldbackAmount(BigDecimal.ZERO);
        e.setHoldbackRate(BigDecimal.ZERO);
        return e;
    }

    private static LedgerEntryJpaEntity entry(Long referenceId, String referenceType,
                                              BigDecimal amount, LocalDate settlementDate) {
        LedgerEntryJpaEntity e = new LedgerEntryJpaEntity();
        e.setReferenceId(referenceId);
        e.setReferenceType(referenceType);
        e.setEntryType("SETTLEMENT_CONFIRMED");
        e.setDebitAccount("ACCOUNTS_PAYABLE");
        e.setCreditAccount("REVENUE");
        e.setAmount(amount);
        e.setStatus("POSTED");
        e.setSettlementDate(settlementDate);
        e.setPostedAt(LocalDateTime.now());
        return e;
    }

    private static PayoutJpaEntity payout(Long settlementId, BigDecimal amount,
                                          PayoutStatus status, LocalDateTime sentAt) {
        LocalDateTime now = LocalDateTime.now();
        return new PayoutJpaEntity(null, settlementId, PayoutType.IMMEDIATE, 1L, amount,
                "004", "9876543210", "테스트셀러",
                status, null, null, 0, null,
                now.minusHours(3), sentAt, null, null, now.minusHours(3), now);
    }
}
