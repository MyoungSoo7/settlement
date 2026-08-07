package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataDisclosureDeliveryRepository
        extends JpaRepository<DisclosureDeliveryJpaEntity, Long> {

    /** 완전판매 게이트 — 해당 청약에 교부 증빙이 존재하는가. */
    boolean existsByApplicationId(java.util.UUID applicationId);
}
