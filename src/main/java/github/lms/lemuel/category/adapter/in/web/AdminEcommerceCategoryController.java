package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.adapter.in.web.dto.*;
import github.lms.lemuel.category.application.service.EcommerceCategoryService;
import github.lms.lemuel.category.domain.EcommerceCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminEcommerceCategoryController {

    private final EcommerceCategoryService categoryService;

    /**
     * 전체 카테고리 트리 조회 (관리자용)
     */
    @GetMapping
    public ResponseEntity<List<EcommerceCategoryResponse>> getAllCategories() {
        List<EcommerceCategory> categories = categoryService.getAllCategoriesTree();
        return ResponseEntity.ok(categories.stream()
                .map(EcommerceCategoryResponse::from)
                .collect(Collectors.toList()));
    }

    /**
     * 카테고리 생성
     */
    @PostMapping
    public ResponseEntity<EcommerceCategoryResponse> createCategory(
            @RequestBody EcommerceCategoryRequest request) {
        EcommerceCategory category = categoryService.createCategory(
                request.getName(),
                request.getSlug(),
                request.getParentId(),
                request.getSortOrder()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 업데이트
     */
    @PutMapping("/{id}")
    public ResponseEntity<EcommerceCategoryResponse> updateCategory(
            @PathVariable Long id,
            @RequestBody EcommerceCategoryUpdateRequest request) {
        EcommerceCategory category = categoryService.updateCategory(
                id,
                request.getName(),
                request.getSlug()
        );
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 이동 (부모 변경)
     */
    @PatchMapping("/{id}/move")
    public ResponseEntity<EcommerceCategoryResponse> moveCategory(
            @PathVariable Long id,
            @RequestBody CategoryMoveRequest request) {
        EcommerceCategory category = categoryService.moveCategory(id, request.getNewParentId());
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 정렬 순서 변경
     */
    @PatchMapping("/{id}/sort")
    public ResponseEntity<EcommerceCategoryResponse> changeSortOrder(
            @PathVariable Long id,
            @RequestBody CategorySortRequest request) {
        EcommerceCategory category = categoryService.changeSortOrder(id, request.getSortOrder());
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 활성화
     */
    @PatchMapping("/{id}/activate")
    public ResponseEntity<EcommerceCategoryResponse> activateCategory(@PathVariable Long id) {
        EcommerceCategory category = categoryService.activateCategory(id);
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 비활성화
     */
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<EcommerceCategoryResponse> deactivateCategory(@PathVariable Long id) {
        EcommerceCategory category = categoryService.deactivateCategory(id);
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 삭제 (soft delete)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
