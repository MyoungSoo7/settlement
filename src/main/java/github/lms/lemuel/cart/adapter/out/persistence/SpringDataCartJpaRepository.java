package github.lms.lemuel.cart.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataCartJpaRepository extends JpaRepository<CartJpaEntity, Long> {

    @Query("SELECT c FROM CartJpaEntity c WHERE c.userId = :userId AND c.status = 'ACTIVE'")
    Optional<CartJpaEntity> findActiveByUserId(@Param("userId") Long userId);
}
