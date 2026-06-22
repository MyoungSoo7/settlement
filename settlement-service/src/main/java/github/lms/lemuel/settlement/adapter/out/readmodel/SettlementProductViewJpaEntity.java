package github.lms.lemuel.settlement.adapter.out.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * settlement 소유 상품 프로젝션 (ADR 0020 Phase 3b).
 * order products(name)를 @Immutable 매핑하던 {@code SettlementProductReadModel} 을 대체한다.
 * ProductChanged 이벤트로 적재되며 settlement 가 소유한다.
 */
@Entity
@Table(name = "settlement_product_view")
@Getter
@Setter
@NoArgsConstructor
public class SettlementProductViewJpaEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(length = 255)
    private String name;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
