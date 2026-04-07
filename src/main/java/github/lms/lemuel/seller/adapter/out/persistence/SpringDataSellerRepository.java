package github.lms.lemuel.seller.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataSellerRepository extends JpaRepository<SellerJpaEntity, Long> {

    Optional<SellerJpaEntity> findByUserId(Long userId);

    Optional<SellerJpaEntity> findByBusinessNumber(String businessNumber);

    List<SellerJpaEntity> findByStatus(String status);
}
