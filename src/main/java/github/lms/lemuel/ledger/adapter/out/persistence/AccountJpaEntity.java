package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor
public class AccountJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AccountJpaEntity(String code, String name, String type) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.createdAt = LocalDateTime.now();
    }

    public AccountJpaEntity(Long id, String code, String name, String type, LocalDateTime createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.type = type;
        this.createdAt = createdAt;
    }
}
