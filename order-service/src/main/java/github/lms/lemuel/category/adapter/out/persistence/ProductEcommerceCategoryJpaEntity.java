package github.lms.lemuel.category.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_ecommerce_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@IdClass(ProductEcommerceCategoryId.class)
public class ProductEcommerceCategoryJpaEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
