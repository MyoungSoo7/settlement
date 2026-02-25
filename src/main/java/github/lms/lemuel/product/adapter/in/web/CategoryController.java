package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.response.CategoryResponse;
import github.lms.lemuel.product.adapter.in.web.request.CreateCategoryRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateCategoryRequest;
import github.lms.lemuel.product.application.port.in.CategoryUseCase;
import github.lms.lemuel.product.domain.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryUseCase categoryUseCase;

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@RequestBody CreateCategoryRequest request) {
        Category category = categoryUseCase.createCategory(
                request.getName(),
                request.getDescription(),
                request.getParentId(),
                request.getDisplayOrder()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(category));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> getCategory(@PathVariable Long id) {
        Category category = categoryUseCase.getCategoryById(id);
        return ResponseEntity.ok(CategoryResponse.from(category));
    }

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getAllCategories() {
        List<Category> categories = categoryUseCase.getAllCategories();
        return ResponseEntity.ok(categories.stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/active")
    public ResponseEntity<List<CategoryResponse>> getActiveCategories() {
        List<Category> categories = categoryUseCase.getActiveCategories();
        return ResponseEntity.ok(categories.stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/root")
    public ResponseEntity<List<CategoryResponse>> getRootCategories() {
        List<Category> categories = categoryUseCase.getRootCategories();
        return ResponseEntity.ok(categories.stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList()));
    }

    @GetMapping("/parent/{parentId}")
    public ResponseEntity<List<CategoryResponse>> getSubCategories(@PathVariable Long parentId) {
        List<Category> categories = categoryUseCase.getSubCategories(parentId);
        return ResponseEntity.ok(categories.stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable Long id,
            @RequestBody UpdateCategoryRequest request) {
        Category category = categoryUseCase.updateCategory(
                id,
                request.getName(),
                request.getDescription(),
                request.getDisplayOrder()
        );
        return ResponseEntity.ok(CategoryResponse.from(category));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activateCategory(@PathVariable Long id) {
        categoryUseCase.activateCategory(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateCategory(@PathVariable Long id) {
        categoryUseCase.deactivateCategory(id);
        return ResponseEntity.ok().build();
    }
}
