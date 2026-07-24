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
    @InjectMocks AccountEntryPersistenceAdapter adapter;

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
    void sellerPayableBalance_는_SELLER_owner의_SELLER_PAYABLE_순잔액을_위임_조회한다() {
        when(repository.netBalanceByOwnerAndAccount(OwnerType.SELLER, "777", GlAccount.SELLER_PAYABLE))
                .thenReturn(new BigDecimal("30000"));

        assertThat(adapter.sellerPayableBalance("777")).isEqualByComparingTo("30000");
        verify(repository).netBalanceByOwnerAndAccount(OwnerType.SELLER, "777", GlAccount.SELLER_PAYABLE);
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
