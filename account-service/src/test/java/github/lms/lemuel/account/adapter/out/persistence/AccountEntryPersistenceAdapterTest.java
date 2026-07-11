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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    void append_는_자연키_선점이_없으면_저장한다() {
        AccountEntry entry = AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000"));
        when(repository.existsBySourceTopicAndRefTypeAndRefId(
                entry.getSourceTopic(), entry.getRefType(), entry.getRefId())).thenReturn(false);

        adapter.append(entry);

        ArgumentCaptor<AccountEntryJpaEntity> captor = ArgumentCaptor.forClass(AccountEntryJpaEntity.class);
        verify(repository).save(captor.capture());
        AccountEntryJpaEntity saved = captor.getValue();
        assertThat(saved.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(saved.getOwnerId()).isEqualTo("55");
        assertThat(saved.getDebitAccount()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
        assertThat(saved.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(saved.getAmount()).isEqualByComparingTo("800000");
        assertThat(saved.getRefType()).isEqualTo("LOAN_DISBURSED");
        assertThat(saved.getRefId()).isEqualTo("L1");
        assertThat(saved.getSourceTopic()).isEqualTo(AccountEntry.TOPIC_LOAN_DISBURSED);
        assertThat(saved.getOccurredAt()).isEqualTo(entry.getOccurredAt());
    }

    @Test
    void append_는_자연키가_이미_있으면_저장을_건너뛴다() {
        AccountEntry entry = AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000"));
        when(repository.existsBySourceTopicAndRefTypeAndRefId(
                entry.getSourceTopic(), entry.getRefType(), entry.getRefId())).thenReturn(true);

        adapter.append(entry);

        verify(repository, never()).save(any());
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
