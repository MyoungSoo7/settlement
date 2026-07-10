package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface AccountEntryRepository extends JpaRepository<AccountEntryJpaEntity, Long> {

    boolean existsBySourceTopicAndRefTypeAndRefId(String sourceTopic, String refType, String refId);

    List<AccountEntryJpaEntity> findByOwnerTypeAndOwnerIdOrderByIdDesc(OwnerType ownerType, String ownerId);

    List<AccountEntryJpaEntity> findByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId, Pageable pageable);

    long countByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId);

    long countByRefType(String refType);

    /** refType 별 금액 합계. 매칭 행이 없으면 COALESCE 로 0 반환(null 미노출). */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from AccountEntryJpaEntity e
            where e.refType = :refType
            """)
    BigDecimal sumAmountByRefType(@Param("refType") String refType);
}
