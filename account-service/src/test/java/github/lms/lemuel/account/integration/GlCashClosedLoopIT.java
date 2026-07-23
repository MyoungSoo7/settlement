package github.lms.lemuel.account.integration;

import github.lms.lemuel.AccountServiceApplication;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.ClearScheduledResidualUseCase;
import github.lms.lemuel.account.application.port.in.ClearScheduledResidualUseCase.ClearingReport;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.application.port.out.AppendAccountEntryPort;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.AccountSummary;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.account.domain.TrialBalance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0026 Option A — GL 현금 폐루프 + 멱등 + 백필의 <b>엔드투엔드 Testcontainers 증명</b>(실 PG + 실 Flyway).
 *
 * <p>단위 테스트가 도메인 계산을 검증한다면, 이 IT 는 실제 원장 적재·자연키 UNIQUE 멱등·기간 조회까지
 * 실 DB 로 관통해 폐루프가 닫히는지 확인한다. 하나의 시나리오를 순차로 수행해 초기 빈 DB 위에서 검증한다.
 */
@SpringBootTest(
        classes = AccountServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class GlCashClosedLoopIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> ACCOUNT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lemuel_account").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", ACCOUNT_DB::getJdbcUrl);
        r.add("spring.datasource.username", ACCOUNT_DB::getUsername);
        r.add("spring.datasource.password", ACCOUNT_DB::getPassword);
        r.add("POSTGRES_USER", ACCOUNT_DB::getUsername);
        r.add("POSTGRES_PASSWORD", ACCOUNT_DB::getPassword);
    }

    @Autowired RecordAccountEntryUseCase recordAccountEntryUseCase;
    @Autowired AppendAccountEntryPort appendAccountEntryPort;
    @Autowired LoadAccountEntryPort loadAccountEntryPort;
    @Autowired AccountQueryUseCase accountQueryUseCase;
    @Autowired ClearScheduledResidualUseCase clearScheduledResidualUseCase;

    private long countRef(String refType, String refId) {
        return loadAccountEntryPort.findAll().stream()
                .filter(e -> e.getRefType().equals(refType) && e.getRefId().equals(refId))
                .count();
    }

    private BigDecimal scheduledNet(String sellerId) {
        return accountQueryUseCase.accountSummary(OwnerType.SELLER, sellerId).balances().stream()
                .filter(b -> b.account() == GlAccount.SETTLEMENT_SCHEDULED)
                .map(AccountSummary.Balance::balance)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("정산생성→지급완료 폐루프 + payout 멱등 + 잔존 예정금 백필이 실 DB 로 닫힌다")
    void closedLoop_idempotency_and_backfill() {
        // ── 1) 폐루프: created(DR CASH/CR PAYABLE) + payoutCompleted(DR PAYABLE/CR CASH) ──
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate("777", "9001", new BigDecimal("43425")));
        recordAccountEntryUseCase.record(AccountEntry.payoutCompleted("777", "7001", new BigDecimal("43425")));

        AccountSummary sellerA = accountQueryUseCase.accountSummary(OwnerType.SELLER, "777");
        BigDecimal cash = sellerA.balances().stream().filter(b -> b.account() == GlAccount.CASH)
                .map(AccountSummary.Balance::balance).findFirst().orElseThrow();
        BigDecimal payable = sellerA.balances().stream().filter(b -> b.account() == GlAccount.SELLER_PAYABLE)
                .map(AccountSummary.Balance::balance).findFirst().orElseThrow();
        assertThat(cash).isEqualByComparingTo("0");     // 플랫폼 pass-through — 순잔액 0
        assertThat(payable).isEqualByComparingTo("0");  // 미지급금 상계 완료

        TrialBalance tb = accountQueryUseCase.trialBalance();
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.normalBalanceRespected()).isTrue();

        // ── 2) payout 멱등: 같은 payoutId 재수신 → 분개 1건(자연키 UNIQUE) ──
        recordAccountEntryUseCase.record(AccountEntry.payoutCompleted("777", "7001", new BigDecimal("43425")));
        assertThat(countRef("PAYOUT_COMPLETED", "7001")).isEqualTo(1L); // 2회차 0건 적재

        // ── 3) 백필: cut-over 잔존 예정금(역사적 DR SETTLEMENT_SCHEDULED) 청산 ──
        appendAccountEntryPort.append(AccountEntry.reconstitute(null, OwnerType.SELLER, "888",
                GlAccount.SETTLEMENT_SCHEDULED, GlAccount.SELLER_PAYABLE, new BigDecimal("50000"),
                "SETTLEMENT_CREATED", "S-legacy", "lemuel.settlement.created", LocalDateTime.now()));
        assertThat(scheduledNet("888")).isEqualByComparingTo("50000"); // 잔존 순차변

        ClearingReport run1 = clearScheduledResidualUseCase.clearResidual();
        assertThat(run1.clearedSellers()).isEqualTo(1);
        assertThat(run1.totalCleared()).isEqualByComparingTo("50000");
        assertThat(scheduledNet("888")).isEqualByComparingTo("0"); // 예정금 청산 완료

        // 반복 실행 결과 불변(멱등) — 2회차는 청산 0건
        ClearingReport run2 = clearScheduledResidualUseCase.clearResidual();
        assertThat(run2.clearedSellers()).isZero();
        assertThat(run2.totalCleared()).isEqualByComparingTo("0");
        assertThat(countRef("SETTLEMENT_SCHED_CLEARING", "888")).isEqualTo(1L); // 청산분개 1건 유지

        // 전사 시산표는 여전히 균형(항등식) — 백필 후에도 대차 무결
        assertThat(accountQueryUseCase.trialBalance().balanced()).isTrue();
    }

    private BigDecimal net(String sellerId, GlAccount account) {
        return accountQueryUseCase.accountSummary(OwnerType.SELLER, sellerId).balances().stream()
                .filter(b -> b.account() == account)
                .map(AccountSummary.Balance::balance)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Option ① 완전정산 라이프사이클 — created(2전표)→즉시지급→회수 발생·상계→유보 해제·지급→환불 소진·조정→취소 후 통제계정 4종 순잔액 0")
    void option1_fullLifecycle_closesAllControlAccounts() {
        final String seller = "700001"; // SELLER owner_id 는 숫자여야 함(chk_account_entry_owner_id_format)

        // ── 정산 A: net=1000, holdback=300, immediate=700 → 즉시지급 + 유보해제·지급 ──
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate(seller, "A", new BigDecimal("700")));
        recordAccountEntryUseCase.record(AccountEntry.settlementHoldbackRecognized(seller, "A", new BigDecimal("300")));
        recordAccountEntryUseCase.record(AccountEntry.payoutCompleted(seller, "pay-A-imm", new BigDecimal("700")));
        recordAccountEntryUseCase.record(AccountEntry.holdbackReleased(seller, "A", new BigDecimal("300")));
        recordAccountEntryUseCase.record(AccountEntry.payoutCompleted(seller, "pay-A-hb", new BigDecimal("300")));

        // ── 정산 B: 지급 후 환불 → 회수채권 발생(R=200) ──
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate(seller, "B", new BigDecimal("500")));
        recordAccountEntryUseCase.record(AccountEntry.payoutCompleted(seller, "pay-B", new BigDecimal("500")));
        recordAccountEntryUseCase.record(AccountEntry.recoveryOpened(seller, "rec1", new BigDecimal("200")));

        // ── 정산 C: 신규 즉시 미지급금으로 회수채권 상계(O=200) ──
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate(seller, "C", new BigDecimal("200")));
        recordAccountEntryUseCase.record(AccountEntry.recoveryOffset(seller, "alloc1", new BigDecimal("200")));

        // ── 정산 D: 확정 전 환불 500 → 유보 소진(Hc=400) + 즉시분 감액(Δ=100) 후 잔여 지급 ──
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate(seller, "D", new BigDecimal("600")));
        recordAccountEntryUseCase.record(AccountEntry.settlementHoldbackRecognized(seller, "D", new BigDecimal("400")));
        recordAccountEntryUseCase.record(AccountEntry.holdbackConsumed(seller, "adjD", new BigDecimal("400")));
        recordAccountEntryUseCase.record(AccountEntry.settlementAdjusted(seller, "adjD", new BigDecimal("100"), GlAccount.SELLER_PAYABLE));
        recordAccountEntryUseCase.record(AccountEntry.payoutCompleted(seller, "pay-D", new BigDecimal("500")));

        // ── 정산 E: 지급 전 전액 취소 → 즉시 잔여·유보 잔여 소멸(2전표) ──
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate(seller, "E", new BigDecimal("210")));
        recordAccountEntryUseCase.record(AccountEntry.settlementHoldbackRecognized(seller, "E", new BigDecimal("90")));
        recordAccountEntryUseCase.record(AccountEntry.settlementCanceledPayable(seller, "E", new BigDecimal("210")));
        recordAccountEntryUseCase.record(AccountEntry.settlementCanceledHoldback(seller, "E", new BigDecimal("90")));

        // ── 통제계정 4종 순잔액 0 (완전정산 봉합, HIGH-1 제거) ──
        assertThat(net(seller, GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("0");
        assertThat(net(seller, GlAccount.HOLDBACK_PAYABLE)).isEqualByComparingTo("0");
        assertThat(net(seller, GlAccount.SELLER_RECOVERY_RECEIVABLE)).isEqualByComparingTo("0");
        assertThat(net(seller, GlAccount.CASH)).isEqualByComparingTo("0"); // 회수 종료 → 현금도 0

        // ── 도메인 불변식 3종 참 ──
        assertThat(accountQueryUseCase.accountSummary(OwnerType.SELLER, seller).fullySettled()).isTrue();
        TrialBalance tb = accountQueryUseCase.trialBalance();
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.normalBalanceRespected()).isTrue();

        // ── /control-recon: 세 통제계정 GL 순잔액 0(전역 폐루프) ──
        var recon = accountQueryUseCase.controlRecon();
        assertThat(recon.sellerPayable()).isEqualByComparingTo("0");
        assertThat(recon.holdbackPayable()).isEqualByComparingTo("0");
        assertThat(recon.recoveryReceivable()).isEqualByComparingTo("0");
        assertThat(recon.balanced()).isTrue();

        // ── 멱등: 신규 이벤트 유형 재수신 → 분개 1건(자연키 UNIQUE) ──
        recordAccountEntryUseCase.record(AccountEntry.holdbackConsumed(seller, "adjD", new BigDecimal("400")));
        recordAccountEntryUseCase.record(AccountEntry.recoveryOpened(seller, "rec1", new BigDecimal("200")));
        recordAccountEntryUseCase.record(AccountEntry.recoveryOffset(seller, "alloc1", new BigDecimal("200")));
        recordAccountEntryUseCase.record(AccountEntry.settlementAdjusted(seller, "adjD", new BigDecimal("100"), GlAccount.SELLER_PAYABLE));
        assertThat(countRef("HOLDBACK_CONSUMED", "adjD")).isEqualTo(1L);
        assertThat(countRef("RECOVERY_OPENED", "rec1")).isEqualTo(1L);
        assertThat(countRef("RECOVERY_OFFSET", "alloc1")).isEqualTo(1L);
        assertThat(countRef("SETTLEMENT_ADJUSTED", "adjD")).isEqualTo(1L);

        // 봉합은 멱등 재수신 후에도 유지
        assertThat(accountQueryUseCase.accountSummary(OwnerType.SELLER, seller).fullySettled()).isTrue();
    }

    @Test
    @DisplayName("HIGH-A(GL 재감사): 지급전 전액/초과 환불→취소는 priorImmediate 상한 캡핑된 정정 금액이라야 통제계정이 0으로 닫힌다")
    void option1_grossRefundBeforePayout_cancels_closesControlAccountsOnlyWithCappedAmount() {
        // 재현 시나리오(독립 GL 재감사 HIGH-A): P=10000, 수수료 3%=300, net=9700, holdback 30%=2910,
        // I=net-holdback=6790. 이 IT 는 이전엔 "취소·초과환불" 경로 자체를 커버하지 않던 맹점이었다.
        //
        // 상한 미적용(버그) 값 payableDelta=refund-consumed=10000-2910=7090 을 실었다면 SELLER_PAYABLE 이
        // 수수료(300)만큼 음수로 잔존했을 것이다 — 그 회귀 증거는 settlement-service 단위테스트
        // (AdjustSettlementForRefundServiceTest/ApplyReconciliationAdjustmentServiceTest 의 HIGH-A 케이스,
        // 수정 전 코드로 되돌리면 실패함을 확인함)가 실제 프로덕션 코드를 호출해 담당한다. 이 IT 는 계정
        // 자체(TrialBalance/AccountSummary)가 실 DB 로 관통해도 여전히 봉합되는지, "전액/초과환불→취소"
        // 경로 자체의 GL 커버리지 공백을 메운다. (버그값을 이 공유 컨텍스트 IT 에 직접 주입하면 실 Postgres
        // 위 글로벌 시산표 정상방향 불변식이 다른 테스트까지 오염시키므로 여기서는 하지 않는다.)
        final String seller = "700002";
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate(seller, "F", new BigDecimal("6790")));
        recordAccountEntryUseCase.record(AccountEntry.settlementHoldbackRecognized(seller, "F", new BigDecimal("2910")));
        recordAccountEntryUseCase.record(AccountEntry.holdbackConsumed(seller, "adjF", new BigDecimal("2910")));
        recordAccountEntryUseCase.record(AccountEntry.settlementAdjusted(
                seller, "adjF", new BigDecimal("6790"), GlAccount.SELLER_PAYABLE)); // 캡핑된(정정) 값

        assertThat(net(seller, GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("0");
        assertThat(net(seller, GlAccount.HOLDBACK_PAYABLE)).isEqualByComparingTo("0");
        assertThat(net(seller, GlAccount.CASH)).isEqualByComparingTo("0"); // 수수료(300)는 GL 밖 경계 — happy-path 와 동일
        assertThat(accountQueryUseCase.accountSummary(OwnerType.SELLER, seller).fullySettled()).isTrue();

        TrialBalance tb = accountQueryUseCase.trialBalance();
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.normalBalanceRespected()).isTrue();
    }

    @Test
    @DisplayName("독립 GL 감사 HIGH #4 봉합: 원천징수 payout 감액분을 Cr WITHHOLDING_PAYABLE 로 닫아야 SELLER_PAYABLE 이 0 으로 봉합된다")
    void withholdingAccrued_closesSellerPayableResidue() {
        // ADR 0027 §B(2026-07-24 정정) — 개인 셀러 원천징수를 실제 payout 지급액에서 공제한다.
        // net=100000, holdback=0 → immediate=100000, withholding=3300(3.3%) → 실지급 96700.
        final String seller = "700003";
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate(seller, "W1", new BigDecimal("100000")));
        recordAccountEntryUseCase.record(AccountEntry.payoutCompleted(seller, "pay-W1", new BigDecimal("96700")));

        // 봉합 전: WITHHOLDING_ACCRUED 미전기 상태에서는 SELLER_PAYABLE 에 원천징수분(3300)이 잔존한다
        // (HIGH #4 재현 — 장부만 줄이고 원천징수 반제 전표가 없으면 통제계정이 닫히지 않는다).
        assertThat(net(seller, GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("3300");

        // 원천징수 예수 반제 전기로 봉합.
        recordAccountEntryUseCase.record(AccountEntry.withholdingAccrued(seller, "W1", new BigDecimal("3300")));

        assertThat(net(seller, GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("0");
        // WITHHOLDING_PAYABLE 은 국세청 미납부 예수부채라 셀러 통제계정과 달리 잔존해야 정상(0 이 아님).
        assertThat(net(seller, GlAccount.WITHHOLDING_PAYABLE)).isEqualByComparingTo("3300");

        TrialBalance tb = accountQueryUseCase.trialBalance();
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.normalBalanceRespected()).isTrue();

        // 멱등: 같은 settlementId 재수신 → 분개 1건(자연키 UNIQUE).
        recordAccountEntryUseCase.record(AccountEntry.withholdingAccrued(seller, "W1", new BigDecimal("3300")));
        assertThat(countRef("WITHHOLDING_ACCRUED", "W1")).isEqualTo(1L);
        assertThat(net(seller, GlAccount.WITHHOLDING_PAYABLE)).isEqualByComparingTo("3300"); // 재수신에도 불변
    }

    @Test
    @DisplayName("기간 확정 시산표는 occurred_at 반개구간 전표만 집계한다")
    void periodTrialBalance_filtersByOccurredAt() {
        // 이 클래스는 컨텍스트를 공유하므로, 기간을 좁혀 이 테스트가 넣은 전표만 대상으로 검증한다.
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate("999", "S-period", new BigDecimal("12000")));

        LocalDateTime from = LocalDateTime.now().minusMinutes(5);
        LocalDateTime to = LocalDateTime.now().plusMinutes(5);
        List<AccountEntry> inWindow = loadAccountEntryPort.findByOccurredAtBetween(from, to);
        assertThat(inWindow).isNotEmpty();

        // 먼 과거 창은 비어 있다(반개구간 경계 확인)
        List<AccountEntry> farPast = loadAccountEntryPort.findByOccurredAtBetween(
                LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2000, 1, 2, 0, 0));
        assertThat(farPast).isEmpty();
    }
}
