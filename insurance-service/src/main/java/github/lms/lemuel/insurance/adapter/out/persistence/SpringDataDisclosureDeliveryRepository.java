package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataDisclosureDeliveryRepository
        extends JpaRepository<DisclosureDeliveryJpaEntity, Long> {
}
