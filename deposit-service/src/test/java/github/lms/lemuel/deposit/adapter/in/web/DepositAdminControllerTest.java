package github.lms.lemuel.deposit.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.deposit.application.port.in.ApplyOffsetUseCase;
import github.lms.lemuel.deposit.application.port.in.CreditDepositUseCase;
import github.lms.lemuel.deposit.application.port.in.DebitDepositUseCase;
import github.lms.lemuel.deposit.application.port.in.ManageShortfallUseCase;
import github.lms.lemuel.deposit.application.port.in.PlaceHoldUseCase;
import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHolderType;
import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import github.lms.lemuel.deposit.domain.exception.InsufficientDepositException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예치금 운영 콘솔 테스트. 검증 대상은 <b>입력 검증과 위임</b>이다 —
 * ADMIN 권한 강제는 SecurityConfig 의 몫이라 이 슬라이스(addFilters=false)에서는 검증하지 않는다.
 *
 * <p>수기 경로에서 특히 중요한 것은 멱등 키를 <b>비워서 통과시키지 않는 것</b>이다. 원장의 L3 방어선
 * {@code UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence)} 은
 * referenceId 가 null 이면 중복을 알아볼 수 없고, 재전송·더블클릭이 잦은 쪽이 바로 수기 경로다.
 */
@WebMvcTest(controllers = DepositAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(github.lms.lemuel.common.config.CacheConfig.class)
class DepositAdminControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreditDepositUseCase creditDepositUseCase;
    @MockitoBean DebitDepositUseCase debitDepositUseCase;
    @MockitoBean PlaceHoldUseCase placeHoldUseCase;
    @MockitoBean ApplyOffsetUseCase applyOffsetUseCase;
    @MockitoBean ManageShortfallUseCase manageShortfallUseCase;

    @Test
    @DisplayName("수기 입금은 본문의 멱등 키를 그대로 유스케이스로 넘긴다")
    void creditDelegatesIdempotencyKey() throws Exception {
        mockMvc.perform(post("/admin/deposits/accounts/777/credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"50000\",\"referenceId\":\"ADJ-1\",\"referenceType\":\"MANUAL\"}"))
                .andExpect(status().isAccepted());

        verify(creditDepositUseCase).credit(eq(777L), any(), eq("ADJ-1"), eq("MANUAL"));
    }

    @Test
    @DisplayName("referenceId 없는 입금은 400 — 멱등 키 없는 수기 요청은 받지 않는다")
    void creditWithoutReferenceIdIsRejected() throws Exception {
        mockMvc.perform(post("/admin/deposits/accounts/777/credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"50000\",\"referenceType\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());

        verify(creditDepositUseCase, never()).credit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("0원 이하 금액은 400 — 부호로 입금·출금을 뒤집는 경로를 막는다")
    void nonPositiveAmountIsRejected() throws Exception {
        mockMvc.perform(post("/admin/deposits/accounts/777/debits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"-1000\",\"referenceId\":\"ADJ-2\",\"referenceType\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());

        verify(debitDepositUseCase, never()).debit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("수기 선점은 hold 를 돌려준다 — 같은 holderReference 재요청이면 기존 hold 가 그대로 온다(멱등)")
    void placeHoldReturnsHold() throws Exception {
        DepositHold hold = DepositHold.place(42L, DepositHolderType.MANUAL, "OPS-1",
                new BigDecimal("30000"), LocalDateTime.now().plusHours(72));
        when(placeHoldUseCase.placeHold(eq(777L), eq(DepositHolderType.MANUAL), eq("OPS-1"), any(), any()))
                .thenReturn(hold);

        mockMvc.perform(post("/admin/deposits/accounts/777/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderType\":\"MANUAL\",\"holderReference\":\"OPS-1\",\"amount\":\"30000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderReference").value("OPS-1"))
                .andExpect(jsonPath("$.originalAmount").value(30000));
    }

    @Test
    @DisplayName("수기 상계는 잔고가 모자라도 202 — 부족분은 실패가 아니라 shortfall 로 기록되는 정상 결과다")
    void applyOffsetIsAcceptedEvenWhenShort() throws Exception {
        mockMvc.perform(post("/admin/deposits/accounts/777/offsets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderType\":\"CARD_AUTHORIZATION\",\"holderReference\":\"AUTH-1\","
                                + "\"offsetAmount\":\"99999999\",\"offsetSequence\":0}"))
                .andExpect(status().isAccepted());

        verify(applyOffsetUseCase).applyOffset(eq(777L), eq(DepositHolderType.CARD_AUTHORIZATION),
                eq("AUTH-1"), any(), anyInt(), any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 부족분 해소 경로 — 자동 배치가 없는 자리라 이 콘솔이 유일한 출구다
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("미해소 부족분 목록은 요청·적용·부족 세 금액을 모두 낸다 — 부족분만 주면 우선순위를 못 정한다")
    void listsOpenShortfallsWithAllThreeAmounts() throws Exception {
        DepositOffsetShortfall s = DepositOffsetShortfall.open(
                777L, DepositHolderType.CARD_AUTHORIZATION, "CAP-1",
                new BigDecimal("3000"), new BigDecimal("1000"), null, OffsetDateTime.now());
        s.assignId(99L);
        when(manageShortfallUseCase.findOpenShortfalls()).thenReturn(List.of(s));

        mockMvc.perform(get("/admin/deposits/shortfalls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].requestedAmount").value(3000))
                .andExpect(jsonPath("$[0].appliedAmount").value(1000))
                .andExpect(jsonPath("$[0].shortfallAmount").value(2000))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    @DisplayName("부족분 해소는 실제 차감액을 돌려준다 — 운영자가 얼마가 움직였는지 응답에서 확인해야 한다")
    void resolveReturnsAppliedAmount() throws Exception {
        when(manageShortfallUseCase.resolveFromAvailable(99L)).thenReturn(new BigDecimal("2000"));

        mockMvc.perform(post("/admin/deposits/shortfalls/99/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAmount").value(2000));
    }

    @Test
    @DisplayName("가용액 부족이면 422 — 200 으로 뭉개면 운영자가 해소됐다고 믿는다")
    void resolveRejectsWhenInsufficient() throws Exception {
        when(manageShortfallUseCase.resolveFromAvailable(99L))
                .thenThrow(new InsufficientDepositException("가용액 부족", "777", "resolveShortfall"));

        // 422 는 이 서비스가 잔고 부족 전반에 쓰는 코드다(ErrorCode.INSUFFICIENT_DEPOSIT).
        // 해소 실패만 409 로 달리 주면 클라이언트가 같은 원인을 두 갈래로 다루게 된다.
        mockMvc.perform(post("/admin/deposits/shortfalls/99/resolve"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("상각은 204 — 잔고가 움직이지 않으므로 돌려줄 금액이 없다")
    void writeOffReturnsNoContent() throws Exception {
        mockMvc.perform(post("/admin/deposits/shortfalls/99/write-off"))
                .andExpect(status().isNoContent());

        verify(manageShortfallUseCase).writeOff(99L);
    }
}
