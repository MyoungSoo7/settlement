package github.lms.lemuel.product.domain;

import java.time.LocalDateTime;

/**
 * StockReservation Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 */
public class StockReservation {

    private Long id;
    private Long productId;
    private Long orderId;
    private Long userId;
    private int quantity;
    private ReservationStatus status;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime releasedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public StockReservation() {
        this.status = ReservationStatus.RESERVED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public StockReservation(Long id, Long productId, Long orderId, Long userId, int quantity,
                            ReservationStatus status, LocalDateTime expiresAt,
                            LocalDateTime confirmedAt, LocalDateTime releasedAt,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.productId = productId;
        this.orderId = orderId;
        this.userId = userId;
        this.quantity = quantity;
        this.status = status != null ? status : ReservationStatus.RESERVED;
        this.expiresAt = expiresAt;
        this.confirmedAt = confirmedAt;
        this.releasedAt = releasedAt;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static StockReservation create(Long productId, Long userId, int quantity, int ttlMinutes) {
        StockReservation reservation = new StockReservation();
        reservation.setProductId(productId);
        reservation.setUserId(userId);
        reservation.setQuantity(quantity);
        reservation.setStatus(ReservationStatus.RESERVED);
        reservation.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        return reservation;
    }

    // 비즈니스 메서드: 예약 확정
    public void confirm(Long orderId) {
        if (this.status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Cannot confirm reservation: status is " + this.status);
        }
        if (isExpired()) {
            throw new IllegalStateException("Cannot confirm reservation: reservation has expired");
        }
        this.status = ReservationStatus.CONFIRMED;
        this.orderId = orderId;
        this.confirmedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 예약 해제
    public void release() {
        if (this.status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Cannot release reservation: status is " + this.status);
        }
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 예약 만료
    public void expire() {
        if (this.status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Cannot expire reservation: status is " + this.status);
        }
        if (!isExpired()) {
            throw new IllegalStateException("Cannot expire reservation: reservation has not expired yet");
        }
        this.status = ReservationStatus.EXPIRED;
        this.updatedAt = LocalDateTime.now();
    }

    // 만료 여부 확인
    public boolean isExpired() {
        return this.expiresAt != null && this.expiresAt.isBefore(LocalDateTime.now());
    }

    // 활성 여부 확인
    public boolean isActive() {
        return this.status == ReservationStatus.RESERVED && !isExpired();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
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
