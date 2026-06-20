package github.lms.lemuel.settlement.adapter.out.readmodel;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of products table for settlement-service.
 * Only includes columns settlement actually needs (name).
 */
@Entity
@Immutable
@Table(name = "products")
@Getter
public class SettlementProductReadModel {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String name;
}
