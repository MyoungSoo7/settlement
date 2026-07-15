package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.product.application.port.in.CategoryUseCase;
import github.lms.lemuel.product.domain.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class CategoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CategoryUseCase categoryUseCase;

    private static Category category(Long id, String name) {
        Category c = Category.create(name, "설명", 1);
        c.assignId(id);
        return c;
    }

    @Test
    @DisplayName("POST /api/categories creates category")
    void createCategory() throws Exception {
        when(categoryUseCase.createCategory(eq("의류"), eq("설명"), isNull(), eq(1)))
                .thenReturn(category(1L, "의류"));

        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"의류","description":"설명","displayOrder":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("의류"));
    }

    @Test
    @DisplayName("GET /api/categories/{id} returns category")
    void getCategory() throws Exception {
        when(categoryUseCase.getCategoryById(1L)).thenReturn(category(1L, "의류"));

        mockMvc.perform(get("/api/categories/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("GET /api/categories returns all")
    void getAllCategories() throws Exception {
        when(categoryUseCase.getAllCategories()).thenReturn(List.of(category(1L, "의류")));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("의류"));
    }

    @Test
    @DisplayName("GET /api/categories/active returns active only")
    void getActiveCategories() throws Exception {
        when(categoryUseCase.getActiveCategories()).thenReturn(List.of(category(1L, "의류")));

        mockMvc.perform(get("/api/categories/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("GET /api/categories/root returns root categories")
    void getRootCategories() throws Exception {
        when(categoryUseCase.getRootCategories()).thenReturn(List.of(category(1L, "의류")));

        mockMvc.perform(get("/api/categories/root"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("GET /api/categories/parent/{parentId} returns sub categories")
    void getSubCategories() throws Exception {
        when(categoryUseCase.getSubCategories(1L)).thenReturn(List.of(category(2L, "상의")));

        mockMvc.perform(get("/api/categories/parent/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("상의"));
    }

    @Test
    @DisplayName("PUT /api/categories/{id} updates category")
    void updateCategory() throws Exception {
        when(categoryUseCase.updateCategory(eq(1L), eq("변경됨"), eq("새설명"), eq(2)))
                .thenReturn(category(1L, "변경됨"));

        mockMvc.perform(put("/api/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"변경됨","description":"새설명","displayOrder":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("변경됨"));
    }

    @Test
    @DisplayName("POST /api/categories/{id}/activate activates")
    void activateCategory() throws Exception {
        mockMvc.perform(post("/api/categories/1/activate"))
                .andExpect(status().isOk());

        verify(categoryUseCase).activateCategory(1L);
    }

    @Test
    @DisplayName("POST /api/categories/{id}/deactivate deactivates")
    void deactivateCategory() throws Exception {
        mockMvc.perform(post("/api/categories/1/deactivate"))
                .andExpect(status().isOk());

        verify(categoryUseCase).deactivateCategory(1L);
    }
}
