package github.lms.lemuel.shipping.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SpringDataSellerShippingPolicyRepository
        extends JpaRepository<SellerShippingPolicyJpaEntity, Long> {

    List<SellerShippingPolicyJpaEntity> findBySellerIdIn(Collection<Long> sellerIds);
}
