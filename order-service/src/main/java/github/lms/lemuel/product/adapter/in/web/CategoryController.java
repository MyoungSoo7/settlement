package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.response.CategoryResponse;
import github.lms.lemuel.product.adapter.in.web.request.CreateCategoryRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateCategoryRequest;
import github.lms.lemuel.product.application.port.in.CategoryUseCase;
import github.lms.lemuel.product.domain.Category;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Product Category", description = "상품 카테고리 CRUD API")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryUseCase categoryUseCase;

    @Operation(summary = "카테고리 생성", description = "상품 카테고리를 생성한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
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

    @Operation(summary = "카테고리 단건 조회", description = "ID로 카테고리를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> getCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id) {
        Category category = categoryUseCase.getCategoryById(id);
        return ResponseEntity.ok(CategoryResponse.from(category));
    }

    @Operation(summary = "전체 카테고리 조회", description = "등록된 모든 카테고리를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getAllCategories() {
        List<Category> categories = categoryUseCase.getAllCategories();
        return ResponseEntity.ok(categories.stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "활성 카테고리 조회", description = "활성 상태의 카테고리만 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/active")
    public ResponseEntity<List<CategoryResponse>> getActiveCategories() {
        List<Category> categories = categoryUseCase.getActiveCategories();
        return ResponseEntity.ok(categories.stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "루트 카테고리 조회", description = "최상위(부모 없음) 카테고리를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/root")
    public ResponseEntity<List<CategoryResponse>> getRootCategories() {
        List<Category> categories = categoryUseCase.getRootCategories();
        return ResponseEntity.ok(categories.stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "하위 카테고리 조회", description = "특정 부모 카테고리의 하위 카테고리를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/parent/{parentId}")
    public ResponseEntity<List<CategoryResponse>> getSubCategories(
            @Parameter(description = "부모 카테고리 ID", required = true) @PathVariable Long parentId) {
        List<Category> categories = categoryUseCase.getSubCategories(parentId);
        return ResponseEntity.ok(categories.stream()
                .map(CategoryResponse::from)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "카테고리 수정", description = "카테고리 이름/설명/정렬 순서를 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id,
            @RequestBody UpdateCategoryRequest request) {
        Category category = categoryUseCase.updateCategory(
                id,
                request.getName(),
                request.getDescription(),
                request.getDisplayOrder()
        );
        return ResponseEntity.ok(CategoryResponse.from(category));
    }

    @Operation(summary = "카테고리 활성화", description = "카테고리를 활성 상태로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "활성화 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activateCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id) {
        categoryUseCase.activateCategory(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "카테고리 비활성화", description = "카테고리를 비활성 상태로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비활성화 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id) {
        categoryUseCase.deactivateCategory(id);
        return ResponseEntity.ok().build();
    }
}
