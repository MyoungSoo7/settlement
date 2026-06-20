package github.lms.lemuel.settlement.adapter.out.readmodel;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of users table for settlement-service.
 * Only includes columns settlement actually needs (email).
 */
@Entity
@Immutable
@Table(name = "users")
@Getter
public class SettlementUserReadModel {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;
}
