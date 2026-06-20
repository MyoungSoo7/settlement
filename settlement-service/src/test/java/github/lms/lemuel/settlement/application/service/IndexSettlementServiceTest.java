package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.EnqueueFailedIndexPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexSettlementServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SettlementSearchIndexPort searchIndexPort;
    @Mock EnqueueFailedIndexPort enqueuePort;
    @InjectMocks IndexSettlementService service;

    @Test @DisplayName("검색 비활성 시 인덱싱 스킵") void index_disabled() {
        when(searchIndexPort.isSearchEnabled()).thenReturn(false);
        service.indexSettlement(1L);
        verify(loadSettlementPort, never()).findById(any());
    }
    @Test @DisplayName("인덱싱 성공") void index_success() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("5000"), LocalDate.now());
        when(searchIndexPort.isSearchEnabled()).thenReturn(true);
        when(loadSettlementPort.findById(1L)).thenReturn(Optional.of(s));
        service.indexSettlement(1L);
        verify(searchIndexPort).indexSettlement(s);
    }
    @Test @DisplayName("인덱싱 실패 시 재시도 큐 등록") void index_failure() {
        when(searchIndexPort.isSearchEnabled()).thenReturn(true);
        when(loadSettlementPort.findById(1L)).thenReturn(Optional.of(
                Settlement.createFromPayment(1L, 10L, new BigDecimal("5000"), LocalDate.now())));
        doThrow(new RuntimeException("ES down")).when(searchIndexPort).indexSettlement(any());
        assertThatThrownBy(() -> service.indexSettlement(1L)).isInstanceOf(RuntimeException.class);
        verify(enqueuePort).enqueueForRetry(1L, "INDEX");
    }
    @Test @DisplayName("벌크 인덱싱 비활성") void bulkIndex_disabled() {
        when(searchIndexPort.isSearchEnabled()).thenReturn(false);
        service.bulkIndexSettlements(List.of(1L, 2L));
        verify(searchIndexPort, never()).bulkIndexSettlements(any());
    }
    @Test @DisplayName("벌크 인덱싱 성공") void bulkIndex_success() {
        Settlement s1 = Settlement.createFromPayment(1L, 10L, new BigDecimal("5000"), LocalDate.now());
        Settlement s2 = Settlement.createFromPayment(2L, 20L, new BigDecimal("3000"), LocalDate.now());
        when(searchIndexPort.isSearchEnabled()).thenReturn(true);
        when(loadSettlementPort.findById(1L)).thenReturn(Optional.of(s1));
        when(loadSettlementPort.findById(2L)).thenReturn(Optional.of(s2));
        service.bulkIndexSettlements(List.of(1L, 2L));
        verify(searchIndexPort).bulkIndexSettlements(argThat(list -> list.size() == 2));
    }
    @Test @DisplayName("삭제 비활성") void delete_disabled() {
        when(searchIndexPort.isSearchEnabled()).thenReturn(false);
        service.deleteSettlementIndex(1L);
        verify(searchIndexPort, never()).deleteSettlementIndex(any());
    }
    @Test @DisplayName("삭제 성공") void delete_success() {
        when(searchIndexPort.isSearchEnabled()).thenReturn(true);
        service.deleteSettlementIndex(1L);
        verify(searchIndexPort).deleteSettlementIndex(1L);
    }
}
