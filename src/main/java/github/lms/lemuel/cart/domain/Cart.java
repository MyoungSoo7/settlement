package github.lms.lemuel.cart.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cart Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 */
public class Cart {

    private Long id;
    private Long userId;
    private CartStatus status;
    private List<CartItem> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public Cart() {
        this.status = CartStatus.ACTIVE;
        this.items = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public Cart(Long id, Long userId, CartStatus status, List<CartItem> items,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.status = status != null ? status : CartStatus.ACTIVE;
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static Cart create(Long userId) {
        Cart cart = new Cart();
        cart.setUserId(userId);
        return cart;
    }

    // 비즈니스 메서드: 아이템 추가
    public CartItem addItem(Long productId, int quantity, BigDecimal price) {
        if (this.status != CartStatus.ACTIVE) {
            throw new IllegalStateException("Cannot add items to a non-active cart");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }

        // 이미 존재하는 상품이면 수량 증가
        Optional<CartItem> existing = findItem(productId);
        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.increaseQuantity(quantity);
            item.setPriceSnapshot(price); // 최신 가격으로 업데이트
            this.updatedAt = LocalDateTime.now();
            return item;
        }

        // 새 아이템 추가
        CartItem newItem = new CartItem();
        newItem.setCartId(this.id);
        newItem.setProductId(productId);
        newItem.setQuantity(quantity);
        newItem.setPriceSnapshot(price);
        this.items.add(newItem);
        this.updatedAt = LocalDateTime.now();
        return newItem;
    }

    // 비즈니스 메서드: 아이템 제거
    public void removeItem(Long productId) {
        if (this.status != CartStatus.ACTIVE) {
            throw new IllegalStateException("Cannot remove items from a non-active cart");
        }
        boolean removed = this.items.removeIf(item -> item.getProductId().equals(productId));
        if (!removed) {
            throw new IllegalArgumentException("Product not found in cart: " + productId);
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 아이템 수량 변경
    public CartItem updateItemQuantity(Long productId, int quantity) {
        if (this.status != CartStatus.ACTIVE) {
            throw new IllegalStateException("Cannot update items in a non-active cart");
        }
        CartItem item = findItem(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found in cart: " + productId));
        item.updateQuantity(quantity);
        this.updatedAt = LocalDateTime.now();
        return item;
    }

    // 비즈니스 메서드: 장바구니 비우기
    public void clearItems() {
        if (this.status != CartStatus.ACTIVE) {
            throw new IllegalStateException("Cannot clear a non-active cart");
        }
        this.items.clear();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 체크아웃
    public void checkout() {
        if (this.status != CartStatus.ACTIVE) {
            throw new IllegalStateException("Only active carts can be checked out");
        }
        if (this.items.isEmpty()) {
            throw new IllegalStateException("Cannot checkout an empty cart");
        }
        this.status = CartStatus.CHECKED_OUT;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 총 금액 계산
    public BigDecimal getTotalAmount() {
        return this.items.stream()
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // 비즈니스 메서드: 총 아이템 수
    public int getTotalItemCount() {
        return this.items.stream()
                .mapToInt(CartItem::getQuantity)
                .sum();
    }

    // 비즈니스 메서드: 특정 상품 찾기
    public Optional<CartItem> findItem(Long productId) {
        return this.items.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst();
    }

    // 상태 확인 메서드
    public boolean isEmpty() {
        return this.items.isEmpty();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public CartStatus getStatus() {
        return status;
    }

    public void setStatus(CartStatus status) {
        this.status = status;
    }

    public List<CartItem> getItems() {
        return new ArrayList<>(items);
    }

    public void setItems(List<CartItem> items) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
