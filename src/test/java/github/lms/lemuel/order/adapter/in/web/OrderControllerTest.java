package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean CreateOrderUseCase createOrderUseCase;
    @MockBean GetOrderUseCase getOrderUseCase;
    @MockBean ChangeOrderStatusUseCase changeOrderStatusUseCase;

    @Test @DisplayName("GET /orders/{id} - 성공") void getOrder() throws Exception {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        when(getOrderUseCase.getOrderById(1L)).thenReturn(order);

        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10000));
    }

    @Test @DisplayName("GET /orders/{id} - 404") void getOrder_notFound() throws Exception {
        when(getOrderUseCase.getOrderById(999L)).thenThrow(new OrderNotFoundException(999L));

        mockMvc.perform(get("/orders/999"))
                .andExpect(status().isNotFound());
    }

    @Test @DisplayName("GET /orders/user/{userId}") void getUserOrders() throws Exception {
        when(getOrderUseCase.getOrdersByUserId(1L)).thenReturn(List.of());

        mockMvc.perform(get("/orders/user/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test @DisplayName("POST /orders - 생성") void createOrder() throws Exception {
        Order order = Order.create(1L, 1L, new BigDecimal("15000"));
        when(createOrderUseCase.createOrder(any())).thenReturn(order);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1, "productId": 1, "amount": 15000}
                                """))
                .andExpect(status().isCreated());
    }

    @Test @DisplayName("PATCH /orders/{id}/cancel") void cancelOrder() throws Exception {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.cancel();
        when(changeOrderStatusUseCase.cancelOrder(1L)).thenReturn(order);

        mockMvc.perform(patch("/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }
}
