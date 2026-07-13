package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class NoOpSettlementSearchAdapterTest {

    private final NoOpSettlementSearchAdapter adapter = new NoOpSettlementSearchAdapter();

    private Settlement buildSettlement() {
        Settlement settlement = Settlement.createFromPayment(
                1L, 2L, new BigDecimal("10000"), LocalDate.of(2026, 4, 1));
        settlement.assignId(1L);
        return settlement;
    }

    @Test
    @DisplayName("indexSettlement — 아무 것도 하지 않고 예외 없이 반환")
    void indexSettlement_noOp() {
        assertThatNoException().isThrownBy(() -> adapter.indexSettlement(buildSettlement()));
    }

    @Test
    @DisplayName("bulkIndexSettlements — 아무 것도 하지 않고 예외 없이 반환")
    void bulkIndexSettlements_noOp() {
        assertThatNoException().isThrownBy(() -> adapter.bulkIndexSettlements(List.of(buildSettlement())));
    }

    @Test
    @DisplayName("deleteSettlementIndex — 아무 것도 하지 않고 예외 없이 반환")
    void deleteSettlementIndex_noOp() {
        assertThatNoException().isThrownBy(() -> adapter.deleteSettlementIndex(1L));
    }

    @Test
    @DisplayName("isSearchEnabled — 항상 false")
    void isSearchEnabled_alwaysFalse() {
        assertThat(adapter.isSearchEnabled()).isFalse();
    }
}
