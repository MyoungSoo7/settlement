package github.lms.lemuel.cart.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataCartItemJpaRepository extends JpaRepository<CartItemJpaEntity, Long> {

    List<CartItemJpaEntity> findByCartId(Long cartId);

    Optional<CartItemJpaEntity> findByCartIdAndProductId(Long cartId, Long productId);

    void deleteByCartIdAndProductId(Long cartId, Long productId);

    void deleteByCartId(Long cartId);
}
