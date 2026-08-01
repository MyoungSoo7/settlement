package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영속 어댑터 단위테스트 — repository 를 목킹해 append 멱등 분기·조회/집계 위임·엔티티↔도메인 매핑을 검증한다.
 * (@DataJpaTest 미사용 — 제한 스캔 부팅 대신 순수 Mockito 로 어댑터 로직만 격리 검증.)
 */
@ExtendWith(MockitoExtension.class)
class AccountEntryPersistenceAdapterTest {

    @Mock AccountEntryRepository repository;
    @Mock AccountBalanceRepository balanceRepository;
    @InjectMocks AccountEntryPersistenceAdapter adapter;

    /** 실체화 잔액 행 스텁 — 어댑터가 balance 만 읽으므로 리플렉션 없이 값만 채운다. */
    private static AccountBalanceJpaEntity balance(BigDecimal value) {
        AccountBalanceJpaEntity stub = org.mockito.Mockito.mock(AccountBalanceJpaEntity.class);
        when(stub.getBalance()).thenReturn(value);
        return stub;
    }

    private static AccountEntryJpaEntity entity(OwnerType ownerType, String ownerId,
                                                GlAccount debit, GlAccount credit, String amount,
                                                String refType, String refId, String topic) {
        return new AccountEntryJpaEntity(ownerType, ownerId, debit, credit, new BigDecimal(amount),
                refType, refId, topic, LocalDateTime.of(2026, 7, 10, 12, 0));
    }

    @Test
    void append_는_ON_CONFLICT_upsert로_자연키_필드를_전달해_삽입한다() {
        // LOW-1: check-then-save(existsBy→save) 대신 레이스-세이프 insertIgnoreConflict 로 위임한다.
        // enum 컬럼은 @Enumerated(STRING) 표현과 맞춰 name() 문자열로 바인딩된다.
        AccountEntry entry = AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000"));

        adapter.append(entry);

        verify(repository).insertIgnoreConflict(
                eq("SELLER"),                        // ownerType.name()
                eq("55"),                            // ownerId
                eq("LOAN_RECEIVABLE"),               // debitAccount.name()
                eq("CASH"),                          // creditAccount.name()
                argThat(a -> a.compareTo(new BigDecimal("800000")) == 0), // amount
                eq("LOAN_DISBURSED"),                // refType
                eq("L1"),                            // refId
                eq(AccountEntry.TOPIC_LOAN_DISBURSED), // sourceTopic
                eq(entry.getOccurredAt()));          // occurredAt
        verify(repository, never()).save(any());     // 앱레벨 exists 선점 경로 제거됨
    }

    @Test
    void append_는_같은_자연키_2회여도_예외없이_멱등_upsert한다() {
        // 동시 중복 수신을 흉내내 같은 자연키로 두 번 append — ON CONFLICT DO NOTHING 이라 둘째도 예외 없이
        // no-op(중복 삽입 0건). 실제 원자성·1건만 삽입은 GlCashClosedLoopIT 가 실 PG 로 증명한다.
        AccountEntry entry = AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000"));

        adapter.append(entry);
        adapter.append(entry);   // 예외 없이 통과해야 한다(TOCTOU DataIntegrityViolation 없음)

        verify(repository, times(2)).insertIgnoreConflict(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void findByOwner_는_엔티티를_도메인으로_매핑한다() {
        when(repository.findByOwnerTypeAndOwnerIdOrderByIdDesc(OwnerType.SELLER, "55")).thenReturn(List.of(
                entity(OwnerType.SELLER, "55", GlAccount.LOAN_RECEIVABLE, GlAccount.CASH,
                        "800000", "LOAN_DISBURSED", "L1", AccountEntry.TOPIC_LOAN_DISBURSED)));

        List<AccountEntry> result = adapter.findByOwner(OwnerType.SELLER, "55");

        assertThat(result).hasSize(1);
        AccountEntry e = result.get(0);
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("55");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getAmount()).isEqualByComparingTo("800000");
        assertThat(e.getRefType()).isEqualTo("LOAN_DISBURSED");
        assertThat(e.getRefId()).isEqualTo("L1");
        assertThat(e.getSourceTopic()).isEqualTo(AccountEntry.TOPIC_LOAN_DISBURSED);
        assertThat(e.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 7, 10, 12, 0));
    }

    @Test
    void findByOwnerPaged_는_id_내림차순_PageRequest로_위임하고_매핑한다() {
        Pageable expected = PageRequest.of(1, 20, Sort.by("id").descending());
        when(repository.findByOwnerTypeAndOwnerId(eq(OwnerType.SELLER), eq("55"), eq(expected))).thenReturn(List.of(
                entity(OwnerType.SELLER, "55", GlAccount.INVESTMENT_ASSET, GlAccount.CASH,
                        "250000", "INVESTMENT_EXECUTED", "O1", AccountEntry.TOPIC_INVESTMENT_EXECUTED)));

        List<AccountEntry> result = adapter.findByOwnerPaged(OwnerType.SELLER, "55", 1, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRefId()).isEqualTo("O1");
        assertThat(result.get(0).getDebitAccount()).isEqualTo(GlAccount.INVESTMENT_ASSET);
        verify(repository).findByOwnerTypeAndOwnerId(OwnerType.SELLER, "55", expected);
    }

    @Test
    void countByOwner_는_repository_카운트를_그대로_반환한다() {
        when(repository.countByOwnerTypeAndOwnerId(OwnerType.CORPORATE, "005930")).thenReturn(7L);

        assertThat(adapter.countByOwner(OwnerType.CORPORATE, "005930")).isEqualTo(7L);
    }

    @Test
    void sumAmountByRefType_는_repository_합계를_그대로_반환한다() {
        when(repository.sumAmountByRefType("LOAN_DISBURSED")).thenReturn(new BigDecimal("1000000"));

        assertThat(adapter.sumAmountByRefType("LOAN_DISBURSED")).isEqualByComparingTo("1000000");
    }

    @Test
    void countByRefType_는_repository_카운트를_그대로_반환한다() {
        when(repository.countByRefType("INVESTMENT_EXECUTED")).thenReturn(4L);

        assertThat(adapter.countByRefType("INVESTMENT_EXECUTED")).isEqualTo(4L);
    }

    @Test
    void sellerPayableBalance_는_실체화_잔액을_PK로_조회한다_재합산하지_않는다() {
        // ADR 0030 Phase 1 — O(셀러 전표 수) 재합산을 (owner, account) 유일키 조회로 교체했다.
        AccountBalanceJpaEntity row = balance(new BigDecimal("30000"));
        when(balanceRepository.findByOwnerTypeAndOwnerIdAndAccount(
                OwnerType.SELLER, "777", GlAccount.SELLER_PAYABLE))
                .thenReturn(Optional.of(row));

        assertThat(adapter.sellerPayableBalance("777")).isEqualByComparingTo("30000");
        verify(repository, never()).netBalanceByOwnerAndAccount(any(), any(), any());
    }

    @Test
    void sellerPayableBalance_는_잔액행이_없으면_0을_돌려준다() {
        when(balanceRepository.findByOwnerTypeAndOwnerIdAndAccount(
                OwnerType.SELLER, "empty", GlAccount.SELLER_PAYABLE))
                .thenReturn(Optional.empty());

        assertThat(adapter.sellerPayableBalance("empty")).isEqualByComparingTo("0");
    }

    @Test
    void balancesOf_는_여러_계정_잔액을_단일_조회로_함께_읽는다() {
        // 코드리뷰 Important 회귀 방지 — balanceOf 를 계정 수만큼 여러 번 호출하던 read-skew 취약
        // 경로 대신, findByOwnerTypeAndOwnerIdAndAccountIn 단일 SELECT 로 두 계정을 함께 읽는지 고정한다.
        AccountBalanceJpaEntity payableRow = balance(new BigDecimal("170000.00"));
        when(payableRow.getAccount()).thenReturn(GlAccount.SELLER_PAYABLE);
        AccountBalanceJpaEntity holdbackRow = balance(new BigDecimal("10000.00"));
        when(holdbackRow.getAccount()).thenReturn(GlAccount.HOLDBACK_PAYABLE);
        when(balanceRepository.findByOwnerTypeAndOwnerIdAndAccountIn(
                OwnerType.SELLER, "777", List.of(GlAccount.SELLER_PAYABLE, GlAccount.HOLDBACK_PAYABLE)))
                .thenReturn(List.of(payableRow, holdbackRow));

        Map<GlAccount, BigDecimal> result = adapter.balancesOf(
                OwnerType.SELLER, "777", List.of(GlAccount.SELLER_PAYABLE, GlAccount.HOLDBACK_PAYABLE));

        assertThat(result.get(GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("170000.00");
        assertThat(result.get(GlAccount.HOLDBACK_PAYABLE)).isEqualByComparingTo("10000.00");
        verify(balanceRepository, never()).findByOwnerTypeAndOwnerIdAndAccount(any(), any(), any());
    }

    @Test
    void balancesOf_는_잔액행이_없는_계정을_0으로_채운다() {
        // IN 쿼리는 잔액 행이 없는 계정을 아예 반환하지 않는다 — 어댑터가 누락분을 0 으로 정규화해야
        // NPE 나 잘못된(과소) 재원을 피한다.
        AccountBalanceJpaEntity payableRow = balance(new BigDecimal("50000"));
        when(payableRow.getAccount()).thenReturn(GlAccount.SELLER_PAYABLE);
        when(balanceRepository.findByOwnerTypeAndOwnerIdAndAccountIn(
                OwnerType.SELLER, "888", List.of(GlAccount.SELLER_PAYABLE, GlAccount.HOLDBACK_PAYABLE)))
                .thenReturn(List.of(payableRow));   // HOLDBACK_PAYABLE 행 없음

        Map<GlAccount, BigDecimal> result = adapter.balancesOf(
                OwnerType.SELLER, "888", List.of(GlAccount.SELLER_PAYABLE, GlAccount.HOLDBACK_PAYABLE));

        assertThat(result.get(GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("50000");
        assertThat(result.get(GlAccount.HOLDBACK_PAYABLE)).isEqualByComparingTo("0");
    }

    @Test
    void append_는_삽입에_성공하면_대변_플러스_차변_마이너스로_잔액을_누적한다() {
        // 전표 한 행 = 차변 1 · 대변 1 이므로 잔액도 두 레그를 같은 tx 에서 함께 움직인다.
        // 부호 규약은 credit-positive (balance = Σcredit − Σdebit).
        AccountEntry entry = AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000"));
        when(repository.insertIgnoreConflict(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        adapter.append(entry);

        // BigDecimal 은 스케일까지 보는 equals 대신 compareTo 로 비교한다(800000 vs 800000.00).
        verify(balanceRepository).upsertDelta(eq("SELLER"), eq("55"), eq("CASH"),
                argThat(d -> d.compareTo(new BigDecimal("800000")) == 0));
        verify(balanceRepository).upsertDelta(eq("SELLER"), eq("55"), eq("LOAN_RECEIVABLE"),
                argThat(d -> d.compareTo(new BigDecimal("-800000")) == 0));
    }

    @Test
    void append_는_중복_수신이면_잔액을_건드리지_않는다() {
        // ★ 이 가드가 빠지면 재수신마다 잔액이 부풀어 원장과 파생 캐시가 어긋난다.
        //   insertIgnoreConflict 가 0행(ON CONFLICT DO NOTHING)일 때는 기표가 없었던 것이다.
        AccountEntry entry = AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000"));
        when(repository.insertIgnoreConflict(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        adapter.append(entry);

        verify(balanceRepository, never()).upsertDelta(any(), any(), any(), any());
    }

    @Test
    void findAll_은_전체_엔티티를_도메인으로_매핑한다() {
        when(repository.findAll()).thenReturn(List.of(
                entity(OwnerType.SELLER, "55", GlAccount.LOAN_RECEIVABLE, GlAccount.CASH,
                        "800000", "LOAN_DISBURSED", "L1", AccountEntry.TOPIC_LOAN_DISBURSED),
                entity(OwnerType.CORPORATE, "005930", GlAccount.CORPORATE_LOAN_RECEIVABLE, GlAccount.CASH,
                        "5000000", "CORP_LOAN_DISBURSED", "9", AccountEntry.TOPIC_CORPORATE_LOAN_DISBURSED)));

        List<AccountEntry> result = adapter.findAll();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AccountEntry::getOwnerType)
                .containsExactly(OwnerType.SELLER, OwnerType.CORPORATE);
        assertThat(result).extracting(AccountEntry::getRefId).containsExactly("L1", "9");
    }
}
