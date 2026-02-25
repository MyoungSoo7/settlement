package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.adapter.in.web.dto.EcommerceCategoryResponse;
import github.lms.lemuel.category.application.service.EcommerceCategoryService;
import github.lms.lemuel.category.domain.EcommerceCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class PublicEcommerceCategoryController {

    private final EcommerceCategoryService categoryService;

    /**
     * 활성 카테고리 트리 조회 (공개용)
     */
    @GetMapping
    public ResponseEntity<List<EcommerceCategoryResponse>> getActiveCategories() {
        List<EcommerceCategory> categories = categoryService.getActiveCategoriesTree();
        return ResponseEntity.ok(categories.stream()
                .map(EcommerceCategoryResponse::from)
                .collect(Collectors.toList()));
    }

    /**
     * 단일 카테고리 조회 (slug)
     */
    @GetMapping("/{slug}")
    public ResponseEntity<EcommerceCategoryResponse> getCategoryBySlug(@PathVariable String slug) {
        EcommerceCategory category = categoryService.getCategoryBySlug(slug);
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }
}
