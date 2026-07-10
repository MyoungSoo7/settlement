package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementSearchAdapterTest {

    @Mock ElasticsearchOperations elasticsearchOperations;
    @Mock SettlementSearchDocumentMapper mapper;

    SettlementSearchAdapter adapter;

    private Settlement buildSettlement(Long id) {
        Settlement settlement = Settlement.createFromPayment(
                id, id + 100, new BigDecimal("10000"), LocalDate.of(2026, 4, 1));
        settlement.setId(id);
        return settlement;
    }

    @Test
    @DisplayName("indexSettlement — 매핑 후 ElasticsearchOperations 로 저장한다")
    void indexSettlement_savesDocument() {
        adapter = new SettlementSearchAdapter(elasticsearchOperations, mapper);
        Settlement settlement = buildSettlement(1L);
        SettlementSearchDocument document = new SettlementSearchDocument();
        document.setSettlementId(1L);
        when(mapper.toDocument(settlement)).thenReturn(document);

        adapter.indexSettlement(settlement);

        verify(elasticsearchOperations).save(document);
    }

    @Test
    @DisplayName("bulkIndexSettlements — 각 정산을 매핑 후 개별 저장한다")
    void bulkIndexSettlements_savesEachDocument() {
        adapter = new SettlementSearchAdapter(elasticsearchOperations, mapper);
        Settlement s1 = buildSettlement(1L);
        Settlement s2 = buildSettlement(2L);
        SettlementSearchDocument d1 = new SettlementSearchDocument();
        d1.setSettlementId(1L);
        SettlementSearchDocument d2 = new SettlementSearchDocument();
        d2.setSettlementId(2L);
        when(mapper.toDocument(s1)).thenReturn(d1);
        when(mapper.toDocument(s2)).thenReturn(d2);

        adapter.bulkIndexSettlements(List.of(s1, s2));

        verify(elasticsearchOperations).save(d1);
        verify(elasticsearchOperations).save(d2);
        verify(elasticsearchOperations, times(2)).save(any(SettlementSearchDocument.class));
    }

    @Test
    @DisplayName("deleteSettlementIndex — id 문자열로 인덱스를 삭제한다")
    void deleteSettlementIndex_deletesById() {
        adapter = new SettlementSearchAdapter(elasticsearchOperations, mapper);

        adapter.deleteSettlementIndex(99L);

        verify(elasticsearchOperations).delete(eq("99"), eq(SettlementSearchDocument.class));
    }

    @Test
    @DisplayName("isSearchEnabled — 기본값은 false")
    void isSearchEnabled_defaultsFalse() {
        adapter = new SettlementSearchAdapter(elasticsearchOperations, mapper);

        assertThat(adapter.isSearchEnabled()).isFalse();
    }

    @Test
    @DisplayName("isSearchEnabled — searchEnabled 필드가 true 면 true 반환")
    void isSearchEnabled_trueWhenFieldTrue() {
        adapter = new SettlementSearchAdapter(elasticsearchOperations, mapper);
        ReflectionTestUtils.setField(adapter, "searchEnabled", true);

        assertThat(adapter.isSearchEnabled()).isTrue();
    }
}
