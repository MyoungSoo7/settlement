package github.lms.lemuel.review.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.review.application.ReviewService;
import github.lms.lemuel.review.domain.Review;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ReviewService reviewService;

    @Test
    @DisplayName("POST /reviews creates review")
    void createReview() throws Exception {
        when(reviewService.createReview(1L, 2L, 5, "great"))
                .thenReturn(review(10L, 1L, 2L, 5, "great"));

        mockMvc.perform(post("/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":1,"userId":2,"rating":5,"content":"great"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.rating").value(5));
    }

    @Test
    @DisplayName("POST /reviews maps duplicate review to 409")
    void createReviewConflict() throws Exception {
        when(reviewService.createReview(1L, 2L, 5, "great"))
                .thenThrow(new IllegalStateException("duplicate"));

        mockMvc.perform(post("/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":1,"userId":2,"rating":5,"content":"great"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("duplicate"));
    }

    @Test
    @DisplayName("GET /reviews/product/{productId} returns product reviews")
    void getProductReviews() throws Exception {
        when(reviewService.getProductReviews(1L))
                .thenReturn(List.of(review(10L, 1L, 2L, 4, "good")));

        mockMvc.perform(get("/reviews/product/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].rating").value(4));
    }

    @Test
    @DisplayName("DELETE /reviews/{id} maps ownership failure to 403")
    void deleteReviewForbidden() throws Exception {
        doThrow(new IllegalStateException("forbidden"))
                .when(reviewService).deleteReview(10L, 2L);

        mockMvc.perform(delete("/reviews/10").param("userId", "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("forbidden"));
    }

    private static Review review(Long id, Long productId, Long userId, int rating, String content) {
        Review review = Review.create(productId, userId, rating, content);
        review.setId(id);
        return review;
    }
}
