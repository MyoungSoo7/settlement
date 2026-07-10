package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.application.service.EcommerceCategoryService;
import github.lms.lemuel.category.domain.EcommerceCategory;
import github.lms.lemuel.common.config.jwt.JwtUtil;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminEcommerceCategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminEcommerceCategoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean EcommerceCategoryService categoryService;

    private EcommerceCategory category() {
        return EcommerceCategory.createRoot("전자제품", "electronics", 5);
    }

    @Test
    @DisplayName("GET /admin/categories: 전체 트리")
    void getAllCategories() throws Exception {
        when(categoryService.getAllCategoriesTree()).thenReturn(List.of(category()));

        mockMvc.perform(get("/admin/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("electronics"));
    }

    @Test
    @DisplayName("POST /admin/categories: 생성 201")
    void createCategory() throws Exception {
        when(categoryService.createCategory(eq("전자제품"), any(), isNull(), eq(5)))
                .thenReturn(category());

        mockMvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"전자제품","slug":"electronics","parentId":null,"sortOrder":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("전자제품"));
    }

    @Test
    @DisplayName("PUT /admin/categories/{id}: 수정")
    void updateCategory() throws Exception {
        when(categoryService.updateCategory(eq(1L), eq("가전"), eq("home"))).thenReturn(category());

        mockMvc.perform(put("/admin/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"가전","slug":"home"}
                                """))
                .andExpect(status().isOk());
        verify(categoryService).updateCategory(1L, "가전", "home");
    }

    @Test
    @DisplayName("PATCH /admin/categories/{id}/move: 부모 변경")
    void moveCategory() throws Exception {
        when(categoryService.moveCategory(eq(1L), eq(2L))).thenReturn(category());

        mockMvc.perform(patch("/admin/categories/1/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newParentId":2}
                                """))
                .andExpect(status().isOk());
        verify(categoryService).moveCategory(1L, 2L);
    }

    @Test
    @DisplayName("PATCH /admin/categories/{id}/sort: 정렬 변경")
    void changeSortOrder() throws Exception {
        when(categoryService.changeSortOrder(eq(1L), eq(3))).thenReturn(category());

        mockMvc.perform(patch("/admin/categories/1/sort")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sortOrder":3}
                                """))
                .andExpect(status().isOk());
        verify(categoryService).changeSortOrder(1L, 3);
    }

    @Test
    @DisplayName("PATCH /admin/categories/{id}/activate·deactivate")
    void activateDeactivate() throws Exception {
        when(categoryService.activateCategory(1L)).thenReturn(category());
        when(categoryService.deactivateCategory(1L)).thenReturn(category());

        mockMvc.perform(patch("/admin/categories/1/activate")).andExpect(status().isOk());
        mockMvc.perform(patch("/admin/categories/1/deactivate")).andExpect(status().isOk());
        verify(categoryService).activateCategory(1L);
        verify(categoryService).deactivateCategory(1L);
    }

    @Test
    @DisplayName("DELETE /admin/categories/{id}: soft delete 204")
    void deleteCategory() throws Exception {
        mockMvc.perform(delete("/admin/categories/1")).andExpect(status().isNoContent());
        verify(categoryService).deleteCategory(1L);
    }
}
