package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.dto.ImageReorderRequest;
import github.lms.lemuel.product.adapter.in.web.dto.ProductImageResponse;
import github.lms.lemuel.product.application.service.ProductImageService;
import github.lms.lemuel.product.domain.ProductImage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/products/{productId}/images")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ProductImageController {

    private final ProductImageService imageService;

    /**
     * 이미지 업로드 (다중)
     */
    @PostMapping
    public ResponseEntity<List<ProductImageResponse>> uploadImages(
            @PathVariable Long productId,
            @RequestParam("files") List<MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<ProductImage> images = imageService.uploadImages(productId, files);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(images.stream()
                        .map(ProductImageResponse::from)
                        .collect(Collectors.toList()));
    }

    /**
     * 이미지 목록 조회
     */
    @GetMapping
    public ResponseEntity<List<ProductImageResponse>> getImages(@PathVariable Long productId) {
        List<ProductImage> images = imageService.getProductImages(productId);
        return ResponseEntity.ok(images.stream()
                .map(ProductImageResponse::from)
                .collect(Collectors.toList()));
    }

    /**
     * 대표 이미지 지정
     */
    @PatchMapping("/{imageId}/primary")
    public ResponseEntity<ProductImageResponse> setPrimaryImage(
            @PathVariable Long productId,
            @PathVariable Long imageId) {

        ProductImage image = imageService.setPrimaryImage(productId, imageId);
        return ResponseEntity.ok(ProductImageResponse.from(image));
    }

    /**
     * 이미지 순서 변경
     */
    @PatchMapping("/reorder")
    public ResponseEntity<List<ProductImageResponse>> reorderImages(
            @PathVariable Long productId,
            @RequestBody ImageReorderRequest request) {

        List<ProductImage> images = imageService.reorderImages(productId, request.getImageIds());
        return ResponseEntity.ok(images.stream()
                .map(ProductImageResponse::from)
                .collect(Collectors.toList()));
    }

    /**
     * 이미지 삭제
     */
    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> deleteImage(
            @PathVariable Long productId,
            @PathVariable Long imageId) {

        imageService.deleteImage(productId, imageId);
        return ResponseEntity.noContent().build();
    }
}
