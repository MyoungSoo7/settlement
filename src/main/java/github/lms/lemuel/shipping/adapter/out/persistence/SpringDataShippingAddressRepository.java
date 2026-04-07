package github.lms.lemuel.shipping.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataShippingAddressRepository extends JpaRepository<ShippingAddressJpaEntity, Long> {

    List<ShippingAddressJpaEntity> findByUserId(Long userId);

    Optional<ShippingAddressJpaEntity> findByUserIdAndIsDefaultTrue(Long userId);
}
