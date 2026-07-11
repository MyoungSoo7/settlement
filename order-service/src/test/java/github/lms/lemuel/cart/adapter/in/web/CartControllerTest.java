package github.lms.lemuel.cart.adapter.in.web;

import github.lms.lemuel.cart.application.port.in.CartUseCase;
import github.lms.lemuel.cart.application.port.in.CheckoutCartUseCase;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.order.domain.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CartController.class)
@AutoConfigureMockMvc(addFilters = false)
class CartControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CartUseCase cartUseCase;
    @MockitoBean CheckoutCartUseCase checkoutUseCase;

    private Cart cartWithItems() {
        Cart cart = Cart.rehydrate(10L, 1L, LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now(),
                List.of(github.lms.lemuel.cart.domain.CartItem
                        .rehydrate(100L, 10L, 500L, null, 2, LocalDateTime.now())));
        return cart;
    }

    @Test
    @DisplayName("GET /users/{id}/cart: 조회(없으면 자동생성)")
    void getCart() throws Exception {
        when(cartUseCase.getOrCreate(1L)).thenReturn(cartWithItems());

        mockMvc.perform(get("/users/1/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.userId").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(500))
                .andExpect(jsonPath("$.cart.totalQuantity").value(2));
    }

    @Test
    @DisplayName("POST /users/{id}/cart/items: 항목 추가")
    void addItem() throws Exception {
        when(cartUseCase.addItem(eq(1L), eq(500L), any(), eq(2))).thenReturn(cartWithItems());

        mockMvc.perform(post("/users/1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":500,"variantId":null,"quantity":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    @DisplayName("PATCH /users/{id}/cart/items: 수량 변경")
    void changeQuantity() throws Exception {
        when(cartUseCase.changeQuantity(eq(1L), eq(500L), any(), eq(5))).thenReturn(cartWithItems());

        mockMvc.perform(patch("/users/1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":500,"variantId":null,"quantity":5}
                                """))
                .andExpect(status().isOk());
        verify(cartUseCase).changeQuantity(eq(1L), eq(500L), any(), eq(5));
    }

    @Test
    @DisplayName("DELETE /users/{id}/cart/items: 항목 삭제")
    void removeItem() throws Exception {
        when(cartUseCase.removeItem(1L, 500L, null)).thenReturn(cartWithItems());

        mockMvc.perform(delete("/users/1/cart/items").param("productId", "500"))
                .andExpect(status().isOk());
        verify(cartUseCase).removeItem(1L, 500L, null);
    }

    @Test
    @DisplayName("DELETE /users/{id}/cart: 비우기")
    void clear() throws Exception {
        when(cartUseCase.clear(1L)).thenReturn(cartWithItems());
        mockMvc.perform(delete("/users/1/cart")).andExpect(status().isOk());
        verify(cartUseCase).clear(1L);
    }

    @Test
    @DisplayName("POST /users/{id}/cart/checkout: 체크아웃 → 주문 요약")
    void checkout() throws Exception {
        Order order = Order.create(1L, 1L, new BigDecimal("20000"));
        order.setId(99L);
        when(checkoutUseCase.checkout(1L)).thenReturn(order);

        mockMvc.perform(post("/users/1/cart/checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(99))
                .andExpect(jsonPath("$.amount").value(20000));
    }
}
