package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetLedgerService — 원장 조회")
class GetLedgerServiceTest {

    @Mock LoadLedgerEntryPort loadPort;
    GetLedgerService service;

    @BeforeEach
    void setUp() {
        service = new GetLedgerService(loadPort);
    }

    @Test
    @DisplayName("getBySettlementId: SETTLEMENT 참조로 위임")
    void getBySettlementId() {
        List<LedgerEntry> entries = List.of();
        when(loadPort.findByReference(500L, ReferenceType.SETTLEMENT)).thenReturn(entries);

        assertThat(service.getBySettlementId(500L)).isSameAs(entries);
    }

    @Test
    @DisplayName("getByRefundId: REFUND 참조로 위임")
    void getByRefundId() {
        List<LedgerEntry> entries = List.of();
        when(loadPort.findByReference(700L, ReferenceType.REFUND)).thenReturn(entries);

        assertThat(service.getByRefundId(700L)).isSameAs(entries);
    }

    @Test
    @DisplayName("getBySettlementDateBetween: 유효 기간은 위임")
    void getBySettlementDateBetween_valid() {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        List<LedgerEntry> entries = List.of();
        when(loadPort.findBySettlementDateBetween(from, to)).thenReturn(entries);

        assertThat(service.getBySettlementDateBetween(from, to)).isSameAs(entries);
    }

    @Test
    @DisplayName("getBySettlementDateBetween: from/to null 이면 IllegalArgumentException")
    void getBySettlementDateBetween_nullRejected() {
        LocalDate d = LocalDate.of(2026, 3, 1);
        assertThatThrownBy(() -> service.getBySettlementDateBetween(null, d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getBySettlementDateBetween(d, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getBySettlementDateBetween: to < from 이면 IllegalArgumentException")
    void getBySettlementDateBetween_reversedRange() {
        LocalDate from = LocalDate.of(2026, 3, 31);
        LocalDate to = LocalDate.of(2026, 3, 1);
        assertThatThrownBy(() -> service.getBySettlementDateBetween(from, to))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
