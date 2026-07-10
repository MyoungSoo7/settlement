package github.lms.lemuel.ledger.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.ledger.application.port.in.GetLedgerUseCase;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.ReferenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LedgerController.class)
@AutoConfigureMockMvc(addFilters = false)
class LedgerControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GetLedgerUseCase getLedgerUseCase;

    private static LedgerEntry sampleEntry(Long referenceId, ReferenceType referenceType) {
        LedgerEntry entry = LedgerEntry.of(referenceId, referenceType, LedgerEntryType.SETTLEMENT_CREATED,
                AccountType.ACCOUNTS_RECEIVABLE, AccountType.REVENUE,
                new BigDecimal("48500"), LocalDate.of(2026, 4, 1), "정산 생성 분개");
        entry.setId(1L);
        return entry;
    }

    @Test
    @DisplayName("GET /api/ledger/settlements/{settlementId} — 정산 원장 조회")
    void getBySettlementId() throws Exception {
        LedgerEntry entry = sampleEntry(10L, ReferenceType.SETTLEMENT);
        when(getLedgerUseCase.getBySettlementId(10L)).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/ledger/settlements/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].referenceId").value(10))
                .andExpect(jsonPath("$[0].referenceType").value("SETTLEMENT"))
                .andExpect(jsonPath("$[0].entryType").value("SETTLEMENT_CREATED"))
                .andExpect(jsonPath("$[0].debitAccount").value("ACCOUNTS_RECEIVABLE"))
                .andExpect(jsonPath("$[0].creditAccount").value("REVENUE"))
                .andExpect(jsonPath("$[0].amount").value(48500.00))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /api/ledger/refunds/{refundId} — 환불 역분개 조회")
    void getByRefundId() throws Exception {
        LedgerEntry entry = sampleEntry(20L, ReferenceType.REFUND);
        when(getLedgerUseCase.getByRefundId(20L)).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/ledger/refunds/20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].referenceType").value("REFUND"));
    }

    @Test
    @DisplayName("GET /api/ledger/entries — 기간별 원장 조회")
    void getEntries() throws Exception {
        LedgerEntry entry = sampleEntry(30L, ReferenceType.SETTLEMENT);
        when(getLedgerUseCase.getBySettlementDateBetween(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
                .thenReturn(List.of(entry));

        mockMvc.perform(get("/api/ledger/entries")
                        .param("from", "2026-04-01")
                        .param("to", "2026-04-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].referenceId").value(30));
    }
}
