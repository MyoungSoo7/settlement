package github.lms.lemuel.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.category.adapter.in.web.dto.EcommerceCategoryRequest;
import github.lms.lemuel.order.adapter.in.web.request.CreateOrderRequest;
import github.lms.lemuel.payment.adapter.in.dto.TossCartConfirmRequest;
import github.lms.lemuel.payment.application.TossPaymentService;
import github.lms.lemuel.product.adapter.in.web.request.CreateProductRequest;
import github.lms.lemuel.user.adapter.in.web.request.CreateUserRequest;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class ShoppingFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private TossPaymentService tossPaymentService;

    @Test
    @DisplayName("전체 쇼핑 흐름 테스트: 유저 생성 -> 로그인 -> 카테고리 생성 -> 상품 등록 -> 주문 -> 장바구니 결제 승인")
    void testFullShoppingFlow() throws Exception {
        // 1. 유저 생성 (Admin role)
        String email = "admin@example.com";
        String password = "password123";
        CreateUserRequest userRequest = new CreateUserRequest(email, password, UserRole.ADMIN);
        MvcResult userResult = mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        
        String userResponse = userResult.getResponse().getContentAsString();
        Long userId = Long.valueOf(objectMapper.readTree(userResponse).get("id").asText());

        // 2. 로그인하여 토큰 획득
        String loginRequest = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginRequest))
                .andExpect(status().isOk())
                .andReturn();
        
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();
        String authHeader = "Bearer " + token;

        // 3. 카테고리 생성
        EcommerceCategoryRequest categoryRequest = new EcommerceCategoryRequest("Electronics", "electronics", null, 1);
        MvcResult categoryResult = mockMvc.perform(post("/admin/categories")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(categoryRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        
        Long categoryId = Long.valueOf(objectMapper.readTree(categoryResult.getResponse().getContentAsString()).get("id").asText());

        // 4. 상품 등록
        CreateProductRequest productRequest1 = new CreateProductRequest("Laptop", "High-end laptop", new BigDecimal("1500.00"), 10);
        MvcResult productResult1 = mockMvc.perform(post("/api/products")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(productRequest1)))
                .andExpect(status().isCreated())
                .andReturn();
        Long productId1 = Long.valueOf(objectMapper.readTree(productResult1.getResponse().getContentAsString()).get("id").asText());

        CreateProductRequest productRequest2 = new CreateProductRequest("Mouse", "Wireless mouse", new BigDecimal("50.00"), 100);
        MvcResult productResult2 = mockMvc.perform(post("/api/products")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(productRequest2)))
                .andExpect(status().isCreated())
                .andReturn();
        Long productId2 = Long.valueOf(objectMapper.readTree(productResult2.getResponse().getContentAsString()).get("id").asText());

        // 5. 주문 생성 (장바구니 시나리오)
        CreateOrderRequest orderRequest1 = new CreateOrderRequest(userId, productId1, new BigDecimal("1500.00"));
        MvcResult orderResult1 = mockMvc.perform(post("/orders")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderRequest1)))
                .andExpect(status().isCreated())
                .andReturn();
        Long orderId1 = Long.valueOf(objectMapper.readTree(orderResult1.getResponse().getContentAsString()).get("id").asText());

        CreateOrderRequest orderRequest2 = new CreateOrderRequest(userId, productId2, new BigDecimal("50.00"));
        MvcResult orderResult2 = mockMvc.perform(post("/orders")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderRequest2)))
                .andExpect(status().isCreated())
                .andReturn();
        Long orderId2 = Long.valueOf(objectMapper.readTree(orderResult2.getResponse().getContentAsString()).get("id").asText());

        // 6. 장바구니 결제 승인 (토스 결제 모의)
        doNothing().when(tossPaymentService).callTossConfirmApi(anyString(), anyString(), anyLong());
        
        TossCartConfirmRequest cartConfirmRequest = new TossCartConfirmRequest();
        cartConfirmRequest.setOrderIds(List.of(orderId1, orderId2));
        cartConfirmRequest.setPaymentKey("test_payment_key");
        cartConfirmRequest.setTossOrderId("toss_order_123");
        cartConfirmRequest.setTotalAmount(1550L);
        
        mockMvc.perform(post("/payments/toss/cart/confirm")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cartConfirmRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 7. 주문 상태 확인
        mockMvc.perform(get("/orders/" + orderId1)
                .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        
        mockMvc.perform(get("/orders/" + orderId2)
                .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("예외 케이스 테스트: 유효하지 않은 주문 금액")
    void testOrderInvalidAmount() throws Exception {
        // 1. 유저 생성 및 로그인
        String email = "error@example.com";
        String password = "password123";
        CreateUserRequest userRequest = new CreateUserRequest(email, password, UserRole.USER);
        MvcResult userResult = mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        Long userId = Long.valueOf(objectMapper.readTree(userResult.getResponse().getContentAsString()).get("id").asText());

        String loginRequest = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginRequest))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();
        String authHeader = "Bearer " + token;

        // 2. 유효하지 않은 금액(0원) 주문 시도
        CreateOrderRequest orderRequest = new CreateOrderRequest(userId, 1L, new BigDecimal("0.00"));
        mockMvc.perform(post("/orders")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderRequest)))
                .andExpect(status().isBadRequest()); // IllegalArgumentException -> 400
    }
}
