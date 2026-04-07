package github.lms.lemuel.wishlist.adapter.in.web;

import github.lms.lemuel.wishlist.adapter.in.web.dto.WishlistItemResponse;
import github.lms.lemuel.wishlist.application.port.in.WishlistUseCase;
import github.lms.lemuel.wishlist.domain.WishlistItem;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 위시리스트 REST API
 * POST   /api/wishlist/{userId}/products/{productId}         - 위시리스트 추가
 * DELETE /api/wishlist/{userId}/products/{productId}         - 위시리스트 삭제
 * GET    /api/wishlist/{userId}                              - 위시리스트 목록
 * GET    /api/wishlist/{userId}/products/{productId}/exists  - 위시리스트 존재 여부
 * GET    /api/wishlist/{userId}/count                        - 위시리스트 수량
 */
@Validated
@RestController
@RequestMapping("/api/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistUseCase wishlistUseCase;

    @PostMapping("/{userId}/products/{productId}")
    public ResponseEntity<WishlistItemResponse> addItem(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId,
            @PathVariable @Positive(message = "상품 ID는 양수여야 합니다") Long productId) {
        WishlistItem item = wishlistUseCase.addItem(userId, productId);
        return ResponseEntity.status(HttpStatus.CREATED).body(WishlistItemResponse.from(item));
    }

    @DeleteMapping("/{userId}/products/{productId}")
    public ResponseEntity<Void> removeItem(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId,
            @PathVariable @Positive(message = "상품 ID는 양수여야 합니다") Long productId) {
        wishlistUseCase.removeItem(userId, productId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<WishlistItemResponse>> getUserWishlist(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        List<WishlistItemResponse> items = wishlistUseCase.getUserWishlist(userId)
                .stream().map(WishlistItemResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(items);
    }

    @GetMapping("/{userId}/products/{productId}/exists")
    public ResponseEntity<Boolean> isInWishlist(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId,
            @PathVariable @Positive(message = "상품 ID는 양수여야 합니다") Long productId) {
        return ResponseEntity.ok(wishlistUseCase.isInWishlist(userId, productId));
    }

    @GetMapping("/{userId}/count")
    public ResponseEntity<Long> getWishlistCount(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        return ResponseEntity.ok(wishlistUseCase.getWishlistCount(userId));
    }
}
