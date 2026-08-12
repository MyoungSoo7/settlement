package github.lms.lemuel.deposit.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.deposit.application.port.in.QueryDepositAccountUseCase;
import github.lms.lemuel.deposit.domain.SellerDepositAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예치금 조회 표면 테스트. 검증 대상은 <b>주체 파생과 상태코드 매핑</b>이다 —
 * 잔고 산식은 도메인 테스트가, 트랜잭션 경계는 {@code DepositServiceTest} 가 이미 고정한다.
 */
@WebMvcTest(controllers = DepositController.class)
@AutoConfigureMockMvc(addFilters = false)
// DepositServiceApplication 이 @EnableCaching 이라 슬라이스에도 CacheManager 빈이 있어야 한다.
@Import(github.lms.lemuel.common.config.CacheConfig.class)
class DepositControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean QueryDepositAccountUseCase queryDepositAccountUseCase;

    private static Authentication userAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "u" + userId + "@example.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** uid claim 이 없는 구(舊) 토큰 주체 — AuthPrincipal.userId() 가 null 일 수 있다. */
    private static Authentication legacyAuth() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "legacy@example.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static SellerDepositAccount account(long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        return SellerDepositAccount.rehydrate(
                42L, sellerId,
                new BigDecimal("70000"), new BigDecimal("30000"), new BigDecimal("100000"),
                3L, now, now);
    }

    @Test
    @DisplayName("내 계좌 조회는 경로가 아니라 JWT 주체에서 sellerId 를 파생한다")
    void myAccountDerivesSellerIdFromToken() throws Exception {
        when(queryDepositAccountUseCase.findBySellerId(777L)).thenReturn(Optional.of(account(777L)));

        mockMvc.perform(get("/api/deposits/accounts/me").principal(userAuth(777L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(777))
                .andExpect(jsonPath("$.available").value(70000))
                .andExpect(jsonPath("$.locked").value(30000))
                .andExpect(jsonPath("$.total").value(100000));

        // 경로에 셀러 식별자가 없다는 것이 이 엔드포인트의 IDOR 방어 전부다.
        verify(queryDepositAccountUseCase).findBySellerId(777L);
    }

    @Test
    @DisplayName("계좌가 없으면 404 — 0원 계좌를 지어내 200 으로 돌려주지 않는다")
    void myAccountReturns404WhenAbsent() throws Exception {
        when(queryDepositAccountUseCase.findBySellerId(777L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/deposits/accounts/me").principal(userAuth(777L)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("주체에 userId 가 없으면 403 — 식별 불가를 익명 통과로 처리하지 않는다")
    void myAccountRejectsPrincipalWithoutUserId() throws Exception {
        mockMvc.perform(get("/api/deposits/accounts/me").principal(legacyAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("운영자 조회는 경로의 sellerId 를 그대로 쓴다 (권한은 SecurityConfig 가 ADMIN·MANAGER 로 잠근다)")
    void adminLookupUsesPathSellerId() throws Exception {
        when(queryDepositAccountUseCase.findBySellerId(999L)).thenReturn(Optional.of(account(999L)));

        mockMvc.perform(get("/api/deposits/accounts/999").principal(userAuth(777L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(999));
    }
}
